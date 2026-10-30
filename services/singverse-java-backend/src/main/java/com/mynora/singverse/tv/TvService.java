package com.mynora.singverse.tv;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.mynora.singverse.auth.crypto.AuthCrypto;
import com.mynora.singverse.media.repository.MediaSongRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

/** Persistent device grants and single-device party aggregates. All writes use XML SQL. */
@Service
public class TvService {
    private final TvMapper db;
    private final ObjectMapper json;
    private final MediaSongRepository songs;

    public TvService(TvMapper db, ObjectMapper json, MediaSongRepository songs) {
        this.db = db;
        this.json = json;
        this.songs = songs;
    }

    static String id() {
        return UUID.randomUUID().toString();
    }

    static ResponseStatusException fail(int status, String message) {
        return new ResponseStatusException(HttpStatus.valueOf(status), message);
    }

    ObjectNode read(Map<String, Object> row) {
        if (row == null) throw fail(404, "记录不存在");
        try {
            return (ObjectNode) json.readTree(row.get("document").toString());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    ObjectNode get(String id) {
        var row = db.find(id);
        var n = read(row);
        n.put("revision", ((Number) row.get("revision")).longValue());
        return n;
    }

    void insert(String id, String kind, String secret, ObjectNode n) {
        n.put("id", id);
        db.insert(id, kind, secret == null ? null : AuthCrypto.hash(secret), n.toString());
    }

    void save(ObjectNode n) {
        if (db.save(n.path("id").asText(), n.toString(), n.path("revision").asLong()) != 1)
            throw fail(409, "状态已变化，请刷新后重试");
        n.put("revision", n.path("revision").asLong() + 1);
    }

    static String text(JsonNode n, String key, int max) {
        String v = n.path(key).asText("").trim();
        if (v.isEmpty() || v.length() > max || v.chars().anyMatch(Character::isISOControl))
            throw fail(400, "字段无效：" + key);
        return v;
    }

    static boolean expired(JsonNode n) {
        return n.has("expiresAt")
                && Instant.parse(n.path("expiresAt").asText()).isBefore(Instant.now());
    }

    public Map<String, Object> serverInfo() {
        var row = db.find("server-instance");
        if (row == null) {
            var n = json.createObjectNode().put("instanceId", id());
            try {
                insert("server-instance", "server", null, n);
            } catch (org.springframework.dao.DuplicateKeyException ignored) {
            }
            row = db.find("server-instance");
        }
        return Map.of(
                "service",
                "singverse",
                "name",
                "SingVerse",
                "apiVersion",
                1,
                "instanceId",
                read(row).path("instanceId").asText());
    }

    public ObjectNode identity(String token) {
        if (token == null || token.length() > 200) throw fail(401, "请重新连接");
        var row = db.secret(AuthCrypto.hash(token));
        if (row == null) throw fail(401, "连接已失效");
        var n = read(row);
        String kind = row.get("kind").toString();
        if (!Set.of("device", "participant").contains(kind)
                || n.path("revoked").asBoolean()
                || expired(n)) throw fail(401, "连接已失效");
        n.put("kind", kind);
        if (kind.equals("participant")) {
            var session = get(n.path("sessionId").asText());
            if (!session.path("status").asText().equals("ACTIVE")) throw fail(401, "聚会已结束");
            var device = get(session.path("deviceId").asText());
            if (device.path("revoked").asBoolean()) throw fail(401, "电视已解绑");
        }
        return n;
    }

    @Transactional
    public ObjectNode authorize(JsonNode input) {
        var pending = db.list("authorization");
        long recent = pending.stream().map(this::read).filter(n -> !expired(n)).count();
        if (recent >= 30) throw fail(429, "授权申请较多，请稍后重试");
        for (var r : pending) if (expired(read(r))) db.delete(r.get("id").toString());
        String key = id(), secret = AuthCrypto.token();
        var n =
                json.createObjectNode()
                        .put("name", text(input, "name", 60))
                        .put(
                                "code",
                                String.format(
                                        "%06d", new java.security.SecureRandom().nextInt(1000000)))
                        .put("expiresAt", Instant.now().plusSeconds(600).toString())
                        .put("status", "PENDING");
        insert(key, "authorization", secret, n);
        return n.deepCopy().put("pollSecret", secret);
    }

    @Transactional
    public ObjectNode poll(String id, String secret) {
        var row = db.find(id);
        var n = read(row);
        if (!"authorization".equals(row.get("kind"))
                || secret == null
                || !AuthCrypto.hash(secret).equals(row.get("secret_hash")))
            throw fail(401, "授权凭据无效");
        if (expired(n)) throw fail(410, "授权已过期，请重新申请");
        if (n.path("status").asText().equals("APPROVED")) {
            var d = get(n.path("deviceId").asText());
            if (d.path("revoked").asBoolean()) throw fail(401, "设备已撤销");
            return json.createObjectNode()
                    .put("status", "APPROVED")
                    .put("deviceId", d.path("id").asText());
        }
        return json.createObjectNode().put("status", n.path("status").asText());
    }

    public List<ObjectNode> adminList(String kind) {
        return db.list(kind).stream()
                .map(
                        r -> {
                            var n = read(r);
                            n.put("revision", ((Number) r.get("revision")).longValue());
                            return n;
                        })
                .filter(n -> !expired(n))
                .toList();
    }

    @Transactional
    public void deny(String id) {
        db.lock(id);
        var n = get(id);
        if (!n.path("status").asText().equals("PENDING")) throw fail(409, "申请已处理");
        n.put("status", "DENIED");
        save(n);
    }

    @Transactional
    public ObjectNode approve(String id, JsonNode input) {
        db.lock(id);
        var n = get(id);
        if (expired(n) || !n.path("status").asText().equals("PENDING")) throw fail(409, "申请已处理或过期");
        if (!text(input, "code", 6).equals(n.path("code").asText())) throw fail(400, "授权码不匹配");
        String device = id();
        var d = json.createObjectNode().put("name", n.path("name").asText()).put("revoked", false);
        insert(device, "device", null, d);
        n.put("deviceId", device).put("status", "APPROVED");
        save(n);
        return d;
    }

    // The poll secret is exchanged for a fresh device token; the request itself authenticates the
    // exchange.
    @Transactional
    public ObjectNode exchange(String id, String secret) {
        var status = poll(id, secret);
        if (!status.path("status").asText().equals("APPROVED")) return status;
        String device = status.path("deviceId").asText();
        db.lock(device);
        var d = get(device);
        String token = AuthCrypto.hash("singverse:device:" + secret);
        if (d.has("credentialId")) return status.put("token", token);
        String credential = id();
        var c = json.createObjectNode().put("deviceId", device);
        insert(credential, "credential", token, c);
        d.put("credentialId", credential);
        save(d);
        return status.put("token", token);
    }

    public ObjectNode authenticate(String token) {
        if (token == null) throw fail(401, "请先连接");
        var row = db.secret(AuthCrypto.hash(token));
        if (row != null && "credential".equals(row.get("kind"))) {
            var c = read(row);
            var d = get(c.path("deviceId").asText());
            if (d.path("revoked").asBoolean()) throw fail(401, "电视已解绑");
            return d.put("kind", "device");
        }
        return identity(token);
    }

    @Transactional
    public void revoke(String id) {
        db.lock(id);
        var n = get(id);
        n.put("revoked", true);
        save(n);
    }

    @Transactional
    public ObjectNode session(ObjectNode actor) {
        if (!actor.path("kind").asText().equals("device")) throw fail(403, "仅电视可以开场");
        String device = actor.path("id").asText();
        db.lock(device);
        var d = get(device);
        if (d.has("sessionId")) {
            var old = get(d.path("sessionId").asText());
            if (old.path("status").asText().equals("ACTIVE")) return old;
        }
        String sid = id();
        var n =
                json.createObjectNode()
                        .put("deviceId", device)
                        .put("status", "ACTIVE")
                        .put("acceptingRequests", true)
                        .put("revision", 0);
        n.putArray("queue");
        n.putArray("commands");
        n.putObject("receipts");
        insert(sid, "session", null, n);
        d.put("sessionId", sid);
        save(d);
        return n;
    }

    @Transactional
    public ObjectNode recover(ObjectNode actor, String sid) {
        if (!actor.path("kind").asText().equals("device")) throw fail(403, "仅电视可恢复");
        db.lock(sid);
        var n = get(sid);
        check(actor, n);
        for (var q : n.path("queue"))
            if (Set.of("PLAYING", "HANDOFF").contains(q.path("status").asText()))
                ((ObjectNode) q).put("status", "WAITING");
        n.remove("playback");
        n.set("commands", json.createArrayNode());
        n.put("queueRevision", n.path("queueRevision").asLong() + 1);
        save(n);
        return publicSnapshot(n, actor);
    }

    public ObjectNode snapshot(ObjectNode actor, String sid) {
        var n = get(sid);
        check(actor, n);
        return publicSnapshot(n, actor);
    }

    void check(ObjectNode actor, ObjectNode n) {
        if (actor.path("kind").asText().equals("device")) {
            if (!n.path("deviceId").asText().equals(actor.path("id").asText()))
                throw fail(403, "不是这台电视的聚会");
        } else if (!n.path("id").asText().equals(actor.path("sessionId").asText()))
            throw fail(403, "不是当前聚会");
    }

    ObjectNode publicSnapshot(ObjectNode n, ObjectNode actor) {
        var copy = n.deepCopy();
        copy.remove("receipts");
        copy.put("stateRevision", n.path("revision").asLong());
        copy.put("revision", n.path("queueRevision").asLong());
        copy.put("actorId", actor.path("id").asText());
        copy.put(
                "host",
                actor.path("kind").asText().equals("device") || actor.path("host").asBoolean());
        if (!actor.path("kind").asText().equals("device")) copy.remove("commands");
        return copy;
    }

    boolean host(ObjectNode a) {
        return a.path("kind").asText().equals("device") || a.path("host").asBoolean();
    }

    @Transactional
    public ObjectNode change(ObjectNode actor, String sid, JsonNode input) {
        db.lock(sid);
        var n = get(sid);
        check(actor, n);
        String request = text(input, "requestId", 80),
                signature =
                        AuthCrypto.hash(
                                json.valueToTree(
                                                new TreeMap<String, Object>(
                                                        json.convertValue(
                                                                input,
                                                                new com.fasterxml.jackson.core.type
                                                                                .TypeReference<
                                                                        Map<String, Object>>() {})))
                                        .toString()),
                receipt = actor.path("id").asText() + ":" + request;
        var receipts = (ObjectNode) n.path("receipts");
        if (receipts.has(receipt)) {
            if (!receipts.path(receipt).asText().equals(signature)) throw fail(409, "请求标识已用于其他操作");
            return publicSnapshot(n, actor);
        }
        if (!n.path("status").asText().equals("ACTIVE")) throw fail(409, "聚会已结束");
        if (!input.has("expectedRevision")
                || input.path("expectedRevision").asLong() != n.path("queueRevision").asLong())
            throw fail(409, "队列已更新，请核对后重试");
        String action = text(input, "action", 30);
        var queue = (ArrayNode) n.path("queue");
        String itemId = input.path("itemId").asText();
        ObjectNode item = null;
        for (var q : queue) if (q.path("id").asText().equals(itemId)) item = (ObjectNode) q;
        if (action.equals("ADD")) {
            if (!n.path("acceptingRequests").asBoolean()) throw fail(409, "本场已暂停接单");
            if (queue.size() >= 500) throw fail(409, "本场队列已满");
            String song = text(input, "songId", 64);
            var projection = projection(songs.get(song));
            var q =
                    json.createObjectNode()
                            .put("id", id())
                            .put("songId", song)
                            .put("ownerId", actor.path("id").asText())
                            .put("ownerName", actor.path("name").asText("电视"))
                            .put("status", "WAITING");
            q.set("song", projection);
            queue.add(q);
        } else if (action.equals("REMOVE")) {
            if (item == null || !item.path("status").asText().equals("WAITING"))
                throw fail(409, "只能移除待唱歌曲");
            if (!host(actor) && !item.path("ownerId").asText().equals(actor.path("id").asText()))
                throw fail(403, "只能撤回自己点的歌");
            item.put("status", "CANCELLED");
        } else {
            if (!host(actor)) throw fail(403, "需要主持权限");
            switch (action) {
                case "TOP", "UP", "DOWN" -> {
                    if (item == null || !item.path("status").asText().equals("WAITING"))
                        throw fail(409, "只能排序待唱歌曲");
                    var list = new ArrayList<JsonNode>();
                    queue.forEach(list::add);
                    int from = list.indexOf(item),
                            to =
                                    action.equals("TOP")
                                            ? 0
                                            : action.equals("UP")
                                                    ? Math.max(0, from - 1)
                                                    : Math.min(list.size() - 1, from + 1);
                    list.remove(from);
                    list.add(to, item);
                    queue.removeAll();
                    list.forEach(queue::add);
                }
                case "LOCK", "UNLOCK", "CLEAR", "END", "HOST", "REVOKE" -> {
                    if (!actor.path("kind").asText().equals("device")) throw fail(403, "请在电视操作");
                    if (action.equals("LOCK") || action.equals("UNLOCK"))
                        n.put("acceptingRequests", action.equals("UNLOCK"));
                    if (action.equals("CLEAR") || action.equals("END"))
                        for (var q : queue)
                            if (q.path("status").asText().equals("WAITING"))
                                ((ObjectNode) q).put("status", "CANCELLED");
                    if (action.equals("END")) {
                        n.put("status", "ENDED").put("acceptingRequests", false);
                        ((ArrayNode) n.path("commands")).removeAll();
                        command(n, "STOP");
                    }
                    if (action.equals("HOST") || action.equals("REVOKE")) {
                        String pid = text(input, "participantId", 64);
                        db.lock(pid);
                        var p = get(pid);
                        if (!p.path("sessionId").asText().equals(sid)) throw fail(403, "参与者不属于本场");
                        p.put(action.equals("HOST") ? "host" : "revoked", true);
                        save(p);
                    }
                }
                case "START", "NEXT" -> {
                    boolean start = action.equals("START");
                    if (start && !actor.path("kind").asText().equals("device"))
                        throw fail(403, "请在电视确认开唱");
                    ObjectNode candidate = null;
                    if (start)
                        for (var q : queue)
                            if (q.path("status").asText().equals("HANDOFF"))
                                candidate = (ObjectNode) q;
                    if (start
                            && n.has("playback")
                            && Set.of("PLAYING", "PAUSED", "BUFFERING")
                                    .contains(n.path("playback").path("state").asText()))
                        throw fail(409, "当前歌曲尚未结束，请先切歌");
                    if (!start) {
                        ((ArrayNode) n.path("commands")).removeAll();
                        for (var q : queue)
                            if (Set.of("HANDOFF", "PLAYING").contains(q.path("status").asText()))
                                ((ObjectNode) q).put("status", "SKIPPED");
                        command(n, "STOP");
                    }
                    if (candidate == null)
                        for (var q : queue)
                            if (q.path("status").asText().equals("WAITING")) {
                                candidate = (ObjectNode) q;
                                break;
                            }
                    n.remove("playback");
                    if (candidate != null) {
                        candidate.put("status", "HANDOFF");
                        var play =
                                json.createObjectNode()
                                        .put("itemId", candidate.path("id").asText())
                                        .put("songId", candidate.path("songId").asText())
                                        .put("attemptId", id())
                                        .put("state", "PREPARING")
                                        .put("confirmed", start)
                                        .put("eventSeq", -1)
                                        .put("positionMs", 0);
                        n.set("playback", play);
                        if (start) command(n, "LOAD");
                    } else command(n, "STOP");
                }

                case "PAUSE", "RESUME", "RESTART", "STOP" -> {
                    if (!n.has("playback") || !n.path("playback").path("confirmed").asBoolean())
                        throw fail(409, "请先在电视确认开唱");
                    if (action.equals("RESTART")) {
                        ((ArrayNode) n.path("commands")).removeAll();
                        ((ObjectNode) n.path("playback"))
                                .put("attemptId", id())
                                .put("eventSeq", -1)
                                .put("positionMs", 0);
                    }
                    command(n, action);
                }
                default -> throw fail(400, "未知操作");
            }
        }
        n.put("queueRevision", n.path("queueRevision").asLong() + 1);
        receipts.put(receipt, signature);
        if (receipts.size() > 1000) throw fail(409, "本场操作数量已达上限，请结束后重新开场");
        save(n);
        return publicSnapshot(n, actor);
    }

    void command(ObjectNode n, String action) {
        var commands = (ArrayNode) n.path("commands");
        if (commands.size() >= 100) throw fail(409, "电视未响应，请等待连接恢复");
        var c = json.createObjectNode().put("id", id()).put("action", action);
        if (n.has("playback")) c.set("playback", n.path("playback").deepCopy());
        commands.add(c);
    }

    @Transactional
    public ObjectNode event(ObjectNode actor, String sid, JsonNode event) {
        if (!actor.path("kind").asText().equals("device")) throw fail(403, "仅电视可报告播放");
        db.lock(sid);
        var n = get(sid);
        check(actor, n);
        String ack = event.path("commandId").asText();
        var commands = (ArrayNode) n.path("commands");
        if (!ack.isEmpty()) {
            int index = -1;
            for (int i = 0; i < commands.size(); i++)
                if (commands.get(i).path("id").asText().equals(ack)) index = i;
            if (index >= 0) for (int i = 0; i <= index; i++) commands.remove(0);
        }
        if (event.has("attemptId")) {
            var p = (ObjectNode) n.get("playback");
            if (p == null || !p.path("attemptId").asText().equals(event.path("attemptId").asText()))
                throw fail(409, "旧播放实例已失效");
            long seq = event.path("eventSeq").asLong(-1);
            if (seq <= p.path("eventSeq").asLong(-1)) return publicSnapshot(n, actor);
            String state = text(event, "state", 20);
            if (!Set.of("PREPARING", "BUFFERING", "PLAYING", "PAUSED", "ENDED", "ERROR", "IDLE")
                    .contains(state)) throw fail(400, "播放状态无效");
            p.put("state", state)
                    .put("eventSeq", seq)
                    .put("positionMs", Math.max(0, event.path("positionMs").asLong()));
            for (var q : n.path("queue"))
                if (q.path("id").asText().equals(p.path("itemId").asText())) {
                    if (state.equals("PLAYING")) ((ObjectNode) q).put("status", "PLAYING");
                    if (state.equals("ENDED") || state.equals("ERROR") || state.equals("IDLE"))
                        ((ObjectNode) q)
                                .put(
                                        "status",
                                        state.equals("ENDED")
                                                ? "COMPLETED"
                                                : state.equals("ERROR") ? "FAILED" : "SKIPPED");
                }
        }
        save(n);
        return publicSnapshot(n, actor);
    }

    @Transactional
    public ObjectNode invitation(ObjectNode actor, String sid) {
        var n = get(sid);
        check(actor, n);
        if (!actor.path("kind").asText().equals("device")
                || !n.path("status").asText().equals("ACTIVE")) throw fail(403, "仅电视主持可刷新邀请");
        db.lock(sid);
        n = get(sid);
        String token = AuthCrypto.token();
        var invite =
                json.createObjectNode()
                        .put("sessionId", sid)
                        .put("expiresAt", Instant.now().plusSeconds(3600).toString());
        String iid = id();
        insert(iid, "invitation", token, invite);
        n.put("invitationId", iid);
        save(n);
        return json.createObjectNode()
                .put("invitation", token)
                .put("expiresAt", invite.path("expiresAt").asText());
    }

    ObjectNode invite(String token) {
        var row = db.secret(AuthCrypto.hash(token));
        if (row == null || !"invitation".equals(row.get("kind"))) throw fail(410, "邀请无效");
        var n = read(row);
        var session = get(n.path("sessionId").asText());
        if (expired(n)
                || !session.path("status").asText().equals("ACTIVE")
                || !session.path("invitationId").asText().equals(n.path("id").asText()))
            throw fail(410, "邀请已过期或刷新");
        if (get(session.path("deviceId").asText()).path("revoked").asBoolean())
            throw fail(410, "电视已解绑");
        return n;
    }

    public ObjectNode preview(String token) {
        var n = invite(token);
        var session = get(n.path("sessionId").asText());
        return json.createObjectNode()
                .put("sessionId", session.path("id").asText())
                .put("deviceName", get(session.path("deviceId").asText()).path("name").asText());
    }

    @Transactional
    public ObjectNode join(JsonNode input) {
        for (var old : db.list("participant"))
            if (expired(read(old))) db.delete(old.get("id").toString());
        var invite = invite(text(input, "invitation", 200));
        if (db.list("participant").size() >= 1000) throw fail(429, "参与人数已达上限");
        String token = AuthCrypto.token();
        var n =
                json.createObjectNode()
                        .put("sessionId", invite.path("sessionId").asText())
                        .put("name", text(input, "name", 30))
                        .put("host", false)
                        .put("revoked", false)
                        .put("expiresAt", Instant.now().plusSeconds(43200).toString());
        insert(id(), "participant", token, n);
        return n.deepCopy().put("token", token);
    }

    public List<ObjectNode> participants(ObjectNode actor, String sid) {
        var n = get(sid);
        check(actor, n);
        if (!actor.path("kind").asText().equals("device")) throw fail(403, "仅电视可查看参与设备");
        return adminList("participant").stream()
                .filter(p -> p.path("sessionId").asText().equals(sid))
                .map(
                        p -> {
                            p.remove("expiresAt");
                            return p;
                        })
                .toList();
    }

    public ObjectNode songDetail(String id) {
        return projection(songs.get(id));
    }

    public ObjectNode projection(com.mynora.singverse.media.dto.MediaSongDtos.Song s) {
        String title = s.result().title();
        var artists = s.result().artists();
        if (s.metadata() != null && !s.metadata().stale() && s.metadata().track() != null) {
            if (s.metadata().fields().contains("title")) title = s.metadata().track().title();
            if (s.metadata().fields().contains("artists")) artists = s.metadata().track().artists();
        }
        var n =
                json.createObjectNode()
                        .put("id", s.id())
                        .put("title", title == null || title.isBlank() ? "未命名歌曲" : title)
                        .put("sourceName", s.sourceName())
                        .put("version", s.result().versionType().name())
                        .put("playability", "UNKNOWN");
        if (s.metadata() != null
                && !s.metadata().stale()
                && s.metadata().fields().contains("cover"))
            n.put("coverPath", "/api/v1/tv/library/songs/" + s.id() + "/cover");
        n.set("artists", json.valueToTree(artists));
        if (s.metadata() != null && !s.metadata().stale() && s.metadata().track() != null) {
            var applied = s.metadata();
            if (applied.fields().contains("album")) n.put("album", applied.track().album());
            if (applied.fields().contains("releaseDate"))
                n.put("releaseDate", applied.track().releaseDate());
            if (applied.fields().contains("durationMs") && applied.track().durationMs() != null)
                n.put("durationMs", applied.track().durationMs());
        }
        n.put("scannedAt", s.scannedAt());
        n.set("classification", json.valueToTree(s.classification()));
        n.put(
                "revision",
                MediaSongRepository.fingerprint(s)
                        + ":"
                        + AuthCrypto.hash(
                                n.toString() + json.valueToTree(s.metadata()).toString()));
        return n;
    }

    public Map<String, Object> artists(String keyword, int page, int size, String artistId) {
        if (page < 0 || size < 1 || size > 100 || keyword.length() > 200)
            throw fail(400, "分页或搜索参数无效");
        var grouped = new TreeMap<String, List<ObjectNode>>();
        for (var row : db.library()) {
            var song = projection(songs.song(row));
            for (var credit : song.path("artists")) {
                String name = credit.asText();
                if (!name.isBlank())
                    grouped.computeIfAbsent(name, k -> new ArrayList<>()).add(song);
            }
        }
        var items = new ArrayList<ObjectNode>();
        for (var entry : grouped.entrySet()) {
            String key = AuthCrypto.hash(entry.getKey());
            if (artistId != null) {
                if (key.equals(artistId)) items.addAll(entry.getValue());
            } else if (entry.getKey()
                    .toLowerCase(Locale.ROOT)
                    .contains(keyword.toLowerCase(Locale.ROOT)))
                items.add(
                        json.createObjectNode()
                                .put("id", key)
                                .put("name", entry.getKey())
                                .put("count", entry.getValue().size()));
        }
        int from = (int) Math.min(items.size(), (long) page * size),
                to = Math.min(items.size(), from + size);
        return Map.of(
                "items",
                items.subList(from, to),
                "page",
                page,
                "size",
                size,
                "totalElements",
                items.size());
    }

    public String resource(ObjectNode actor, String song) {
        if (!actor.path("kind").asText().equals("device")) throw fail(403, "仅电视可取播放媒体");
        songs.get(song);
        return "/api/v1/tv/media/" + song;
    }
}
