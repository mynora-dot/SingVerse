package com.mynora.singverse.compute;

import static com.mynora.singverse.compute.ComputeNodeDtos.*;

import com.fasterxml.jackson.databind.*;
import com.mynora.singverse.media.metadata.BoundedResponse;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Bounded, server-side unicast discovery. Browser input never chooses a pairing endpoint. */
@Component
public class ComputeLanDiscovery {
    public record Scan(String network, Integer port) {}
    public record Pair(String discoveryId, long revision) {}
    public record Item(String id, String name, String endpoint, String nodeId, boolean pairingAvailable, String message) {}
    public record Result(List<Item> items, String message) {}
    private record Candidate(URI endpoint, String nodeId, long expires) {}
    private record Advertisement(String nodeId, String name, String owner, boolean enabled) {}
    private final ComputeNodeSettings settings;
    private final ComputeNodeProbe probe;
    private final ComputeCallback callback;
    private final ComputePairingIdentity identity;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(400))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final Map<String, Candidate> candidates = new ConcurrentHashMap<>();
    private final AtomicBoolean scanning = new AtomicBoolean();
    public ComputeLanDiscovery(ComputeNodeSettings settings, ComputeNodeProbe probe, ComputeCallback callback,
            ComputePairingIdentity identity, ObjectMapper json) {
        this.settings = settings; this.probe = probe; this.callback = callback; this.identity = identity; this.json = json;
    }

    public Result discover(Scan input) {
        if (!scanning.compareAndSet(false, true)) throw failure(429, "正在扫描局域网，请稍后重试");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(9500);
        var pool = Executors.newFixedThreadPool(32, runnable -> { var thread = new Thread(runnable, "compute-lan-discovery"); thread.setDaemon(true); return thread; });
        var pending = new ArrayList<Future<Item>>();
        Future<String> client = null;
        try {
            int[] ports = ports(input == null ? null : input.port());
            var targets = targets(input == null ? null : input.network(), ports, input != null && input.port() != null, pool, deadline);
            client = pool.submit(identity::clientId);
            long available = deadline - System.nanoTime();
            if (available <= 0) throw failure(503, "扫描准备超时，请重试");
            String clientId = client.get(available, TimeUnit.NANOSECONDS);
            purge();
            var completed = new ExecutorCompletionService<Item>(pool);
            for (URI endpoint : targets) pending.add(completed.submit(() -> inspect(endpoint, clientId)));
            var result = new ArrayList<Item>();
            for (int remaining = pending.size(); remaining > 0 && result.size() < 32; remaining--) {
                long nanos = deadline - System.nanoTime(); if (nanos <= 0) break;
                var future = completed.poll(nanos, TimeUnit.NANOSECONDS); if (future == null) break;
                try { var item = future.get(); if (item != null) result.add(item); }
                catch (ExecutionException ignored) { /* Unreachable hosts and unrelated services are normal scan outcomes. */ }
            }
            result.sort(Comparator.comparing(Item::endpoint));
            return new Result(List.copyOf(result), result.isEmpty()
                    ? "未发现计算节点；可指定业务机器可达的局域网网段后重试，并检查节点端口与配对开关"
                    : "发现 " + result.size() + " 个计算节点，选择节点后将检查认证与业务回源");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw failure(503, "扫描已中断，请重试");
        } catch (TimeoutException error) { throw failure(503, "扫描准备超时，请重试"); }
        catch (ExecutionException error) { throw failure(503, "节点配对身份暂不可用，请检查服务密钥配置");
        } finally {
            if (client != null) client.cancel(true);
            for (var future : pending) future.cancel(true);
            pool.shutdownNow(); scanning.set(false);
        }
    }

    public Settings pair(Pair input) {
        if (input == null || input.discoveryId() == null || input.discoveryId().length() > 64 || input.revision() < 0)
            throw failure(400, "请选择刚刚发现的计算节点");
        var previous = settings.view();
        if (previous.revision() != input.revision()) throw failure(409, "配置已被修改，请刷新后重试");
        purge(); var candidate = candidates.get(input.discoveryId());
        if (candidate == null) throw failure(404, "发现结果已过期，请重新扫描");
        requirePrivate(candidate.endpoint());
        try {
            String clientId = identity.clientId();
            var advertised = advertisement(exchange(candidate.endpoint(), "/internal/v1/lan/discovery", null, 1500));
            if (!candidate.nodeId().equals(advertised.nodeId())) throw failure(409, "节点身份已变化，请重新扫描");
            if (!available(advertised, clientId)) throw failure(409, "节点尚未开放配对，或已由其他业务服务管理");
            var request = json.createObjectNode().put("nodeId", candidate.nodeId()).put("clientId", clientId);
            var challenge = exchange(candidate.endpoint(), "/internal/v1/lan/challenge", request, 4000);
            String nonce = challenge.path("challenge").asText();
            if (!candidate.nodeId().equals(challenge.path("nodeId").asText()) || !nonce.matches("[a-f0-9]{64}"))
                throw failure(502, "节点配对挑战无效，请重新扫描");
            var proof = identity.proof(candidate.nodeId(), nonce);
            request.put("publicKey", proof.publicKey()).put("challenge", nonce).put("signature", proof.signature());
            var response = exchange(candidate.endpoint(), "/internal/v1/lan/pair", request, 4000);
            if (!candidate.nodeId().equals(response.path("nodeId").asText()) || !nonce.equals(response.path("challenge").asText())
                    || !response.path("encryptedToken").isTextual() || response.path("encryptedToken").asText().length() > 1024)
                throw failure(502, "节点配对响应无效，请重新扫描");
            String token = identity.decrypt(candidate.nodeId(), nonce, response.path("encryptedToken").asText());
            var check = probe.check(new Connection(candidate.endpoint(), token, previous.mappings()));
            if (!"AVAILABLE".equals(check.status())) throw failure(422, check.message());
            return settings.save(new Save("MANUAL", candidate.endpoint().toString(), token, false, previous.mappings(), input.revision()));
        } catch (ResponseStatusException error) { throw error; }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw failure(503, "节点配对已中断，请重试"); }
        catch (Exception error) { throw failure(502, "节点配对失败或超时，请检查节点配对开关后重新扫描"); }
    }

    private Item inspect(URI endpoint, String clientId) {
        try {
            requirePrivate(endpoint);
            var value = advertisement(exchange(endpoint, "/internal/v1/lan/discovery", null, 500));
            String id = UUID.randomUUID().toString();
            candidates.put(id, new Candidate(endpoint, value.nodeId(), System.nanoTime() + TimeUnit.MINUTES.toNanos(2)));
            boolean allowed = available(value, clientId);
            return new Item(id, value.name(), endpoint.toString(), value.nodeId(), allowed,
                    allowed ? (value.owner().isEmpty() ? "可配对，无需填写连接密钥" : "已属于本业务服务，可恢复连接")
                            : (!value.enabled() ? "节点未开放局域网配对" : "节点已由其他业务服务管理"));
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); return null; }
        catch (Exception error) { return null; }
    }
    private JsonNode exchange(URI endpoint, String path, JsonNode payload, int timeout) throws Exception {
        var request = HttpRequest.newBuilder(endpoint.resolve(path)).timeout(Duration.ofMillis(timeout)).header("Accept", "application/json");
        if (payload == null) request.GET();
        else request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload.toString()));
        var response = http.sendAsync(request.build(), info -> new BoundedResponse(8192));
        try {
            var received = response.get(timeout, TimeUnit.MILLISECONDS);
            if (received.statusCode() != 200) throw failure(502, "节点未接受配对请求，请检查配对开关后重新扫描");
            var body = json.readTree(received.body()); if (body == null || !body.isObject()) throw new IllegalArgumentException();
            return body;
        } finally { response.cancel(true); }
    }
    private static Advertisement advertisement(JsonNode value) {
        String id = value.path("nodeId").asText(), name = value.path("name").asText(), owner = value.path("ownerId").asText();
        if (!"singverse-compute".equals(value.path("service").asText()) || !"1".equals(value.path("apiVersion").asText())
                || !id.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")
                || name.isBlank() || name.length() > 128 || name.chars().anyMatch(Character::isISOControl)
                || !(owner.isEmpty() || owner.matches("[a-f0-9]{64}")) || !value.path("pairingEnabled").isBoolean()) throw new IllegalArgumentException();
        return new Advertisement(id, name, owner, value.path("pairingEnabled").asBoolean());
    }
    private static boolean available(Advertisement value, String clientId) { return value.enabled() && (value.owner().equals(clientId) || value.owner().isEmpty()); }
    private void purge() {
        long now = System.nanoTime(); candidates.entrySet().removeIf(entry -> entry.getValue().expires() < now);
        if (candidates.size() > 256) candidates.clear();
    }
    private LinkedHashSet<URI> targets(String network, int[] ports, boolean explicitPort, ExecutorService resolver, long deadline) {
        var result = new LinkedHashSet<URI>();
        if (network != null && !network.isBlank()) { for (var address : subnet(network)) add(result, address, ports); return result; }
        var networks = new LinkedHashSet<String>();
        try { addOrigin(callback.origin(), true, result, networks, ports, explicitPort, resolver, deadline); } catch (ResponseStatusException ignored) { }
        var current = settings.view();
        addOrigin(current.endpoint(), false, result, networks, ports, explicitPort, resolver, deadline);
        addOrigin(current.automaticEndpoint(), false, result, networks, ports, explicitPort, resolver, deadline);
        try {
            for (var nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback()) continue;
                for (var address : Collections.list(nic.getInetAddresses())) if (privateV4(address)) networks.add(prefix(address));
            }
        } catch (SocketException ignored) { }
        int count = 0;
        for (var value : networks) { if (count++ == 3) break; for (var address : subnet(value)) add(result, address, ports); }
        var hosts = new HashSet<String>();
        result.removeIf(uri -> {
            if (hosts.contains(uri.getHost())) return false;
            if (hosts.size() >= 768) return true;
            hosts.add(uri.getHost()); return false;
        });
        return result;
    }
    private static void addOrigin(String value, boolean callback, Set<URI> targets, Set<String> networks, int[] ports, boolean explicitPort, ExecutorService resolver, long deadline) {
        if (value == null || value.isBlank()) return;
        Future<InetAddress[]> future = null;
        try {
            var origin = ComputeNodeSettings.origin(value);
            future = resolver.submit(() -> InetAddress.getAllByName(origin.getHost()));
            long wait = Math.min(TimeUnit.MILLISECONDS.toNanos(400), deadline - System.nanoTime()); if (wait <= 0) return;
            for (var address : future.get(wait, TimeUnit.NANOSECONDS)) {
                if (!privateAddress(address)) continue;
                if (privateV4(address)) networks.add(prefix(address));
                if (callback || explicitPort) add(targets, address, ports);
                else targets.add(endpoint(origin.getScheme(), address, origin.getPort() < 0 ? (origin.getScheme().equals("https") ? 443 : 80) : origin.getPort()));
            }
        } catch (Exception ignored) { }
        finally { if (future != null) future.cancel(true); }
    }
    static List<InetAddress> subnet(String value) {
        try {
            if (value.length() > 32 || !value.matches("[0-9.]+/(?:2[4-9]|3[0-2])")) throw new IllegalArgumentException();
            var parts = value.split("/"); byte[] octets = new byte[4]; var numbers = parts[0].split("\\.", -1);
            if (numbers.length != 4) throw new IllegalArgumentException();
            for (int i = 0; i < 4; i++) { int n = Integer.parseInt(numbers[i]); if (n < 0 || n > 255 || !numbers[i].equals(Integer.toString(n))) throw new IllegalArgumentException(); octets[i] = (byte) n; }
            var first = InetAddress.getByAddress(octets); int bits = Integer.parseInt(parts[1]);
            if (!privateV4(first) && !(first.isLoopbackAddress() && bits == 32)) throw new IllegalArgumentException();
            long ip = 0; for (byte b : octets) ip = (ip << 8) | (b & 255);
            int count = 1 << (32 - bits); long base = ip & (0xffff_ffffL << (32 - bits)); var result = new ArrayList<InetAddress>();
            for (int i = 0; i < count; i++) { long item = base + i; result.add(InetAddress.getByAddress(new byte[]{(byte)(item >> 24), (byte)(item >> 16), (byte)(item >> 8), (byte)item})); }
            return result;
        } catch (Exception error) { throw failure(400, "扫描网段仅支持私有IPv4 /24～/32，或本机回环地址 /32"); }
    }
    private static int[] ports(Integer port) {
        if (port == null) return new int[]{18090, 8090};
        if (port < 1 || port > 65535) throw failure(400, "节点端口应为1～65535");
        return new int[]{port};
    }
    private static void add(Set<URI> values, InetAddress address, int[] ports) { for (int port : ports) values.add(endpoint("http", address, port)); }
    private static URI endpoint(String scheme, InetAddress address, int port) {
        try { return new URI(scheme, null, address.getHostAddress(), port, null, null, null); }
        catch (URISyntaxException impossible) { throw new IllegalArgumentException(impossible); }
    }
    static void requirePrivate(URI uri) {
        try {
            String host = uri.getHost().replace("[", "").replace("]", "");
            if (!(host.matches("[0-9.]+") || host.contains(":")) || host.contains("%")) throw new IllegalArgumentException();
            if (!privateAddress(InetAddress.getByName(host))) throw new IllegalArgumentException();
        } catch (Exception error) { throw failure(400, "自动配对仅支持本机或受限局域网地址"); }
    }
    private static boolean privateAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        return address.isLoopbackAddress() || privateV4(address) || bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }
    private static boolean privateV4(InetAddress address) {
        byte[] b = address.getAddress();
        return b.length == 4 && ((b[0] & 255) == 10 || (b[0] & 255) == 172 && (b[1] & 255) >= 16 && (b[1] & 255) <= 31 || (b[0] & 255) == 192 && (b[1] & 255) == 168);
    }
    private static String prefix(InetAddress address) { var b = address.getAddress(); return (b[0]&255) + "." + (b[1]&255) + "." + (b[2]&255) + ".0/24"; }
    private static ResponseStatusException failure(int code, String message) { return new ResponseStatusException(HttpStatus.valueOf(code), message); }
}
