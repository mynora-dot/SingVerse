#!/usr/bin/env python3
"""Capture the Java implementation baseline before replacing it.

This manifest tracks scope, not test success. No route is marked implemented
merely because a Go handler was registered.
"""
import argparse
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "services/singverse-java-backend/src/main"
OUTPUT = ROOT / "contracts/go-migration-baseline.json"
MAPPING = re.compile(r"@(Get|Post|Put|Delete|Patch|Request)Mapping\b(?:\s*\(([^)]*)\))?")


def annotation_paths(args):
    # consumes/produces strings are media types, never additional route paths.
    value = r'(\{(?:[^"}]|"[^"]*")*\}|"[^"]*")'
    named = re.search(r"(?:\bpath|\bvalue)\s*=\s*" + value, args)
    if named:
        return re.findall(r'"([^"\n]*)"', named.group(1)) or [""]
    positional = re.match(r'\s*' + value, args)
    return re.findall(r'"([^"\n]*)"', positional.group(1)) if positional else [""]


def inventory():
    api = json.loads((ROOT / "contracts/openapi/singverse.openapi.json").read_text())
    public = sorted([
        {"method": method.upper(), "path": path}
        for path, item in api["paths"].items()
        for method in item if method in {"get", "post", "put", "patch", "delete", "head", "options"}
    ], key=lambda item: (item["path"], item["method"]))
    routes, jobs, queries = [], [], []
    for file in sorted((JAVA / "java").rglob("*.java")):
        text = file.read_text()
        prefix = ""
        class_position = text.find("public class ")
        for match in MAPPING.finditer(text):
            kind, args = match.group(1), match.group(2) or ""
            paths = annotation_paths(args)
            if kind == "Request" and match.start() < class_position:
                prefix = paths[0]
                continue
            methods = [kind.upper()] if kind != "Request" else re.findall(r"RequestMethod\.(\w+)", args)
            for method in methods:
                for path in paths:
                    routes.append({"method": method, "path": prefix + path, "source": str(file.relative_to(ROOT))})
        for match in re.finditer(r"@Scheduled\s*\(([^)]*)\)", text):
            jobs.append({"source": str(file.relative_to(ROOT)), "line": text[:match.start()].count("\n") + 1, "schedule": match.group(1).strip()})
    for file in sorted((JAVA / "resources/mappers").rglob("*.xml")):
        mapper = ET.parse(file).getroot()
        for query in mapper:
            if query.tag in {"select", "update", "insert", "delete"}:
                queries.append({"namespace": mapper.attrib["namespace"], "id": query.attrib["id"], "dialect": query.get("databaseId"), "kind": query.tag})
    return {"schemaVersion": 1, "publicOperations": public, "javaRoutes": routes, "scheduledJobs": jobs, "sqlStatements": queries}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    if not JAVA.is_dir():
        if args.write:
            raise SystemExit("Java source was retired; the historical baseline must not be overwritten")
        value = json.loads(OUTPUT.read_text())
        api = json.loads((ROOT / "contracts/openapi/singverse.openapi.json").read_text())
        public = sorted([{"method": method.upper(), "path": path}
                         for path, item in api["paths"].items()
                         for method in item if method in {"get", "post", "put", "patch", "delete", "head", "options"}],
                        key=lambda item: (item["path"], item["method"]))
        if public != value["publicOperations"]:
            raise SystemExit("OpenAPI diverged from the frozen migration baseline; review the contract change explicitly")
        print(json.dumps({"historicalJavaBaseline": True, **{key: len(items) for key, items in value.items() if isinstance(items, list)}}))
        return
    value = inventory()
    if args.write:
        OUTPUT.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")
    elif json.loads(OUTPUT.read_text()) != value:
        raise SystemExit("Java/OpenAPI migration baseline changed; review and regenerate explicitly")
    print(json.dumps({key: len(items) for key, items in value.items() if isinstance(items, list)}))


if __name__ == "__main__":
    main()
