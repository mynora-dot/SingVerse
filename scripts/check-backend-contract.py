#!/usr/bin/env python3
"""Run common Java/Go HTTP contracts against an owned, fresh isolated backend.

This creates/revokes one television, one party and administrator sessions. Never
point it at an existing deployment. No external Python packages are required.
"""

import argparse
from dataclasses import dataclass
from http.cookiejar import CookieJar
import json
import re
import sys
from urllib.error import HTTPError
from urllib.parse import urljoin, urlsplit
from urllib.request import (
    HTTPCookieProcessor,
    HTTPRedirectHandler,
    ProxyHandler,
    Request,
    build_opener,
)


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


@dataclass
class Reply:
    status: int
    headers: object
    raw: bytes

    def json(self):
        return json.loads(self.raw)


class Browser:
    def __init__(self, base):
        self.base = base
        self.jar = CookieJar()
        self.opener = build_opener(
            ProxyHandler({}), HTTPCookieProcessor(self.jar), NoRedirect()
        )
        self.assertions = 0

    def call(self, method, path, data=None, *, status=200, csrf=False, headers=None):
        values = {"Accept": "application/json"}
        if headers:
            values.update(headers)
        if csrf:
            values["X-CSRF-Token"] = self.call("GET", "/api/v1/auth/csrf").json()["token"]
        raw = None
        if data is not None:
            raw = json.dumps(data, ensure_ascii=False).encode()
            values["Content-Type"] = "application/json"
        req = Request(self.base + path, data=raw, headers=values, method=method)
        try:
            response = self.opener.open(req, timeout=20)
        except HTTPError as error:
            response = error
        with response:
            reply = Reply(response.code, response.headers, response.read(4_000_001))
        self.check(reply.status == status, f"{method} {path}: HTTP {reply.status}, expected {status}")
        self.check(len(reply.raw) <= 4_000_000, f"{path}: unexpectedly large response")
        return reply

    def check(self, condition, message):
        if not condition:
            raise AssertionError(message)
        self.assertions += 1

    def problem(self, reply, status):
        self.check("application/problem+json" in reply.headers.get("Content-Type", ""), "error is not Problem Details")
        value = reply.json()
        self.check(value.get("status") == status, "Problem Details status mismatch")
        self.check(all(isinstance(value.get(key), str) and value[key] for key in ("type", "title", "detail")), "Problem Details missing type/title/detail")


def run(base):
    admin, public = Browser(base), Browser(base)
    health = public.call("GET", "/api/health").json()
    public.check(health == {"status": "UP"}, "public health exposed details or is not UP")
    for path in ("/admin/", "/party/"):
        page = public.call("GET", path)
        public.check("text/html" in page.headers.get("Content-Type", ""), f"{path}: HTML MIME missing")
        markup = page.raw.decode("utf-8")
        public.check("<html" in markup.lower(), f"{path}: missing HTML document")
        scripts = re.findall(r'<script\b[^>]*\bsrc=["\']([^"\']+)["\']', markup, re.I)
        public.check(bool(scripts), f"{path}: frontend script missing")
        asset_url = urljoin(base + path, scripts[0])
        public.check(urlsplit(asset_url).netloc == urlsplit(base).netloc, "frontend asset left same origin")
        asset = public.call("GET", urlsplit(asset_url).path)
        public.check("javascript" in asset.headers.get("Content-Type", ""), "frontend script MIME invalid")
        public.check(bool(asset.raw) and not asset.raw.lstrip().startswith(b"<"), "frontend script returned HTML fallback")

    public.problem(public.call("GET", "/api/v1/system/parameters", status=401), 401)
    public.problem(public.call("GET", "/api/v1/auth/session", status=401), 401)
    public.problem(public.call("GET", "/api/v1/tv/home", status=401), 401)
    admin.problem(admin.call("POST", "/api/v1/auth/login", {"username": "admin", "password": "admin"}, status=403), 403)
    login = admin.call("POST", "/api/v1/auth/login", {"username": "admin", "password": "admin"}, csrf=True)
    admin.check(login.json().get("state") == "authenticated", "fresh administrator login state differs")
    session = admin.call("GET", "/api/v1/auth/session").json()
    admin.check(session.get("username") == "admin" and session.get("defaultPassword") is True, "requires a fresh default administrator in an isolated deployment")
    sources = admin.call("GET", "/api/v1/media-sources").json()
    admin.check(sources.get("totalElements") == 0 and sources.get("items") == [], "requires an empty isolated media library")
    session_cookies = [cookie for cookie in admin.jar if cookie.name == "singverse_session"]
    admin.check(len(session_cookies) == 1, "session Cookie missing")
    old_cookie = "singverse_session=" + session_cookies[0].value
    admin.check("HttpOnly" in session_cookies[0]._rest, "session Cookie missing HttpOnly")
    admin.check(session_cookies[0]._rest.get("SameSite", "").lower() == "lax", "session Cookie missing SameSite=Lax")

    admin.problem(admin.call("POST", "/api/v1/auth/logout", status=403), 403)
    admin.call("GET", "/api/v1/auth/session")
    admin.problem(admin.call("POST", "/api/v1/auth/logout", csrf=True, headers={"Origin": "https://cross-origin.invalid"}, status=403), 403)
    admin.call("GET", "/api/v1/auth/session")
    admin.problem(admin.call("GET", "/api/v1/media-songs?page=invalid", status=400), 400)
    admin.problem(admin.call("GET", "/api/v1/playlists?size=0", status=400), 400)
    admin.problem(admin.call("GET", "/api/v1/tv/home", status=401), 401)

    grant = public.call("POST", "/api/v1/device-authorizations", {"name": "隔离契约电视"}).json()
    public.check(grant.get("status") == "PENDING" and len(grant.get("code", "")) == 6, "television grant contract differs")
    grant_path = "/api/v1/device-authorizations/" + grant["id"] + "/poll"
    pending = public.call("POST", grant_path, {"pollSecret": grant["pollSecret"]}).json()
    public.check(pending.get("status") == "PENDING" and "token" not in pending, "pending grant revealed device token")
    admin.call("POST", "/api/v1/admin/device-authorizations/" + grant["id"] + "/approve", {"code": grant["code"]}, csrf=True)
    authorized = public.call("POST", grant_path, {"pollSecret": grant["pollSecret"]}).json()
    public.check(isinstance(authorized.get("token"), str) and bool(authorized["token"]), "approved television token missing")
    device = {"Authorization": "Bearer " + authorized["token"]}
    public.call("GET", "/api/v1/tv/home", headers=device)
    public.problem(public.call("GET", "/api/v1/system/parameters", headers=device, status=401), 401)
    public.problem(public.call("GET", "/api/v1/tv/library/songs?size=0", headers=device, status=400), 400)
    public.problem(public.call("GET", "/api/v1/tv/home", headers={"Authorization": "Bearer invalid-contract-token"}, status=401), 401)
    tv_session = public.call("POST", "/api/v1/tv/sessions", headers=device).json()
    invitation = public.call("POST", "/api/v1/tv/sessions/" + tv_session["id"] + "/invitations", headers=device).json()
    public.call("POST", "/api/v1/party/invitations/preview", {"invitation": invitation["invitation"]})
    guest = public.call("POST", "/api/v1/party/join", {"invitation": invitation["invitation"], "name": "隔离亲友"}).json()
    participant = {"Authorization": "Bearer " + guest["token"]}
    public.call("GET", "/api/v1/party/library/songs", headers=participant)
    public.problem(public.call("GET", "/api/v1/tv/home", headers=participant, status=403), 403)
    public.problem(public.call("GET", "/api/v1/tv/releases/current", headers=participant, status=403), 403)
    admin.call("DELETE", "/api/v1/admin/tv-devices/" + authorized["deviceId"], csrf=True)
    public.problem(public.call("GET", "/api/v1/tv/home", headers=device, status=401), 401)
    public.problem(public.call("GET", "/api/v1/party/library/songs", headers=participant, status=401), 401)

    admin.call("POST", "/api/v1/auth/logout", csrf=True)
    admin.problem(admin.call("GET", "/api/v1/auth/session", status=401), 401)
    public.problem(public.call("GET", "/api/v1/auth/session", headers={"Cookie": old_cookie}, status=401), 401)
    print(json.dumps({"result": "passed", "assertions": admin.assertions + public.assertions, "checks": ["health", "frontend-assets", "administrator-cookie", "csrf-origin", "logout-revocation", "television-authorization", "party-identity", "bearer-boundary", "problem-details", "query-validation"]}, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True, help="fresh isolated backend HTTP origin")
    parser.add_argument("--isolated", action="store_true", required=True, help="confirm this is a newly created, owned test deployment")
    args = parser.parse_args()
    address = urlsplit(args.base_url)
    if address.scheme != "http" or address.hostname not in ("127.0.0.1", "::1", "localhost") or address.username or address.password or address.path not in ("", "/") or address.query or address.fragment:
        parser.error("--base-url must be a loopback HTTP origin for an isolated backend")
    try:
        run(args.base_url.rstrip("/"))
    except (AssertionError, KeyError, ValueError, OSError) as error:
        print("backend contract failed: " + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
