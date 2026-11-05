package com.mynora.singverse.compute;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.mynora.singverse.compute.ComputeNodeDtos.*;

import com.fasterxml.jackson.databind.*;
import com.mynora.singverse.auth.crypto.AuthCrypto;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("sqlite") @DirtiesContext
class ComputeLanDiscoveryTest {
    @TempDir static Path root;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("singverse.sqlite.path", () -> root.resolve("identity.db").toString());
        p.add("singverse.auth.key-file", () -> root.resolve("master.key").toString());
        p.add("singverse.transcoding.cache-directory", () -> root.resolve("cache").toString());
        p.add("singverse.classification.worker-enabled", () -> false);
    }
    @Autowired ComputePairingIdentity identity;
    @Autowired ComputePairingIdentityMapper mapper;
    @Autowired ComputeNodeMapper computeMapper;
    @Autowired AuthCrypto crypto;
    @Autowired ObjectMapper json;
    ComputeNodeSettings settings;
    ComputeNodeProbe probe;
    ComputeLanDiscovery service;
    com.sun.net.httpserver.HttpServer fixture;
    final String nodeId = UUID.randomUUID().toString(), challenge = "a1".repeat(32), token = "isolated-paired-bearer-" + "b".repeat(40);
    volatile String owner = "", responseNonce;
    volatile boolean redirect, corrupt, disabled, slowBody;
    volatile int requests;

    @BeforeEach void prepare() throws Exception {
        settings = mock(ComputeNodeSettings.class); probe = mock(ComputeNodeProbe.class);
        var callback = mock(ComputeCallback.class);
        when(callback.origin()).thenReturn("http://127.0.0.1:8080");
        when(settings.view()).thenReturn(new Settings("AUTO", "", false, List.of(), "", false, 4));
        when(settings.save(any())).thenAnswer(call -> { Save save = call.getArgument(0); return new Settings(save.mode(), save.endpoint(), true, save.mappings(), "", false, 5); });
        when(probe.check(any())).thenReturn(new Check("AVAILABLE", "available", List.of("media-tags"), 1));
        service = new ComputeLanDiscovery(settings, probe, callback, identity, json);
        responseNonce = challenge;
        fixture = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext("/", exchange -> {
            requests++;
            try {
                String path = exchange.getRequestURI().getPath(); JsonNode response;
                if (redirect) { exchange.getResponseHeaders().set("Location", "http://169.254.169.254/"); exchange.sendResponseHeaders(302, -1); return; }
                if (slowBody) { exchange.sendResponseHeaders(200, 100); exchange.getResponseBody().write('{'); exchange.getResponseBody().flush(); Thread.sleep(1500); return; }
                if (path.endsWith("/discovery")) response = json.createObjectNode().put("service", "singverse-compute").put("apiVersion", "1")
                        .put("nodeId", nodeId).put("name", "隔离计算节点").put("ownerId", owner).put("pairingEnabled", !disabled);
                else {
                    var input = json.readTree(exchange.getRequestBody().readAllBytes());
                    assertThat(input.path("nodeId").asText()).isEqualTo(nodeId);
                    assertThat(input.path("clientId").asText()).isEqualTo(identity.clientId());
                    if (path.endsWith("/challenge")) response = json.createObjectNode().put("nodeId", nodeId).put("challenge", responseNonce);
                    else {
                        var key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(input.path("publicKey").asText())));
                        String clientId = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getEncoded()));
                        assertThat(clientId).isEqualTo(identity.clientId());
                        var signature = Signature.getInstance("RSASSA-PSS"); signature.setParameter(ComputePairingIdentity.PSS); signature.initVerify(key);
                        signature.update((ComputePairingIdentity.PROTOCOL + "\n" + nodeId + "\n" + challenge + "\n" + clientId).getBytes(StandardCharsets.UTF_8));
                        assertThat(signature.verify(Base64.getDecoder().decode(input.path("signature").asText()))).isTrue();
                        var cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
                        cipher.init(Cipher.ENCRYPT_MODE, key, new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256,
                                new PSource.PSpecified((ComputePairingIdentity.PROTOCOL + ":" + nodeId + ":" + challenge).getBytes(StandardCharsets.UTF_8))));
                        byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)); if (corrupt) encrypted[0] ^= 1;
                        response = json.createObjectNode().put("nodeId", nodeId).put("challenge", challenge).put("encryptedToken", Base64.getEncoder().encodeToString(encrypted));
                    }
                }
                byte[] bytes = json.writeValueAsBytes(response); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            } catch (Throwable error) { exchange.sendResponseHeaders(500, -1); }
            finally { exchange.close(); }
        }); fixture.start();
    }
    @AfterEach void stop() { fixture.stop(0); }
    ComputeLanDiscovery.Result scan() { return service.discover(new ComputeLanDiscovery.Scan("127.0.0.1/32", fixture.getAddress().getPort())); }

    @Test void discoversAndPairsWithServerOnlyCredentialsAndRecoversPersistentIdentity() throws Exception {
        var result = scan(); assertThat(result.items()).hasSize(1);
        var found = result.items().get(0);
        assertThat(found.pairingAvailable()).isTrue();
        assertThat(json.writeValueAsString(result)).doesNotContain(token, identity.clientId(), "publicKey", "challenge", "ownerId");
        var saved = service.pair(new ComputeLanDiscovery.Pair(found.id(), 4));
        assertThat(saved.hasToken()).isTrue(); assertThat(saved.endpoint()).isEqualTo("http://127.0.0.1:" + fixture.getAddress().getPort());
        var captured = org.mockito.ArgumentCaptor.forClass(Save.class); verify(settings).save(captured.capture());
        assertThat(captured.getValue().token()).isEqualTo(token);
        assertThat(json.writeValueAsString(saved)).doesNotContain(token);
        assertThat(new ComputePairingIdentity(mapper, crypto).clientId()).isEqualTo(identity.clientId());
        var stored = mapper.find();
        assertThat(stored.get("private_key_encrypted").toString()).startsWith("v1:").doesNotContain("PRIVATE KEY");
        assertThat(computeMapper.secrets()).contains(stored.get("private_key_encrypted").toString());
        owner = identity.clientId(); assertThat(scan().items().get(0).pairingAvailable()).isTrue();
        disabled = true;
        assertThat(scan().items().get(0).pairingAvailable()).isFalse();
    }

    @Test void staleRevisionAndFailedCallbackNeverReplacePriorConnection() {
        String id = scan().items().get(0).id(); int before = requests;
        assertThatThrownBy(() -> service.pair(new ComputeLanDiscovery.Pair(id, 3))).hasMessageContaining("409");
        assertThat(requests).isEqualTo(before); verify(settings, never()).save(any());
        when(probe.check(any())).thenReturn(new Check("CALLBACK_FAILED", "业务回源不可用", List.of(), 1));
        assertThatThrownBy(() -> service.pair(new ComputeLanDiscovery.Pair(id, 4))).hasMessageContaining("422", "业务回源不可用");
        verify(settings, never()).save(any());
        assertThatThrownBy(() -> service.pair(new ComputeLanDiscovery.Pair(UUID.randomUUID().toString(), 4))).hasMessageContaining("404");
    }

    @Test void refusesOtherOwnersMalformedChallengesCiphertextsAndRedirects() {
        owner = "b".repeat(64); var item = scan().items().get(0); assertThat(item.pairingAvailable()).isFalse(); String otherId = item.id();
        assertThatThrownBy(() -> service.pair(new ComputeLanDiscovery.Pair(otherId, 4))).hasMessageContaining("409");
        owner = ""; String id = scan().items().get(0).id(); responseNonce = "invalid";
        assertThatThrownBy(() -> service.pair(new ComputeLanDiscovery.Pair(id, 4))).hasMessageContaining("502", "挑战");
        responseNonce = challenge; corrupt = true;
        assertThatThrownBy(() -> service.pair(new ComputeLanDiscovery.Pair(id, 4))).hasMessageContaining("502", "凭据");
        verify(settings, never()).save(any());
        redirect = true; assertThat(scan().items()).isEmpty();
    }

    @Test void limitsExplicitNetworksAndNeverPairsWithPublicOrLinkLocalAddresses() {
        assertThat(ComputeLanDiscovery.subnet("192.168.2.0/24")).hasSize(256);
        assertThat(ComputeLanDiscovery.subnet("10.0.0.1/32")).hasSize(1);
        assertThat(ComputeLanDiscovery.subnet("172.16.1.128/25")).hasSize(128);
        for (String value : List.of("8.8.8.8/32", "169.254.1.0/24", "127.0.0.0/24", "10.0.0.0/23", "::1/128", "192.168.01.0/24", "10.0.0.1"))
            assertThatThrownBy(() -> service.discover(new ComputeLanDiscovery.Scan(value, 18090))).hasMessageContaining("400");
        for (String value : List.of("https://8.8.8.8:443", "http://169.254.169.254", "http://localhost:8090", "http://[fe80::1]:8090", "http://[::ffff:8.8.8.8]:8090"))
            assertThatThrownBy(() -> ComputeLanDiscovery.requirePrivate(URI.create(value))).hasMessageContaining("400");
        for (String value : List.of("http://127.0.0.1:8090", "http://192.168.1.1:8090", "http://[fd00::1]:8090", "http://[::ffff:192.168.1.1]:8090"))
            assertThatCode(() -> ComputeLanDiscovery.requirePrivate(URI.create(value))).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.discover(new ComputeLanDiscovery.Scan("127.0.0.1/32", 0))).hasMessageContaining("400");
    }

    @Test void initializesOneIdentityForConcurrentRequests() throws Exception {
        try (var pool = Executors.newFixedThreadPool(8)) {
            var calls = new ArrayList<Callable<String>>(); for (int i = 0; i < 16; i++) calls.add(identity::clientId);
            var clients = new HashSet<String>(); for (var result : pool.invokeAll(calls)) clients.add(result.get());
            assertThat(clients).containsExactly(identity.clientId());
        }
    }

    @Test void boundsDiscoveryTimeoutEvenWhenHeadersArriveButBodyStalls() {
        identity.clientId(); slowBody = true; long start = System.nanoTime();
        assertThat(scan().items()).isEmpty();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(1300);
    }
}
