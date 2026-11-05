package com.mynora.singverse.media.transcoding;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mynora.singverse.compute.ComputeNodeDtos.Connection;
import com.mynora.singverse.compute.ComputeNodeSettings;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class TranscodeClientTest {
    @Test void cacheStorageAndLeaseConflictsKeepTheirHttpStatus() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/transcoding/cache/clear", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer isolated-cache-token");
            var body = "{\"title\":\"private source details\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            int status = exchange.getRequestMethod().equals("POST") ? 507 : 409;
            exchange.sendResponseHeaders(status, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            var settings = mock(ComputeNodeSettings.class);
            var connection = new Connection(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "isolated-cache-token", List.of());
            var client = new TranscodeClient(settings, new ObjectMapper());
            assertThatThrownBy(() -> client.call(connection, "/internal/v1/transcoding/cache/clear", "POST", null))
                    .hasMessageContaining("507", "缓存或磁盘空间不足").hasMessageNotContaining("private source details");
            assertThatThrownBy(() -> client.call(connection, "/internal/v1/transcoding/cache/clear", "DELETE", null))
                    .hasMessageContaining("409").hasMessageNotContaining("private source details");
        } finally { server.stop(0); }
    }

    @Test void fixedFastClassifierDownloadsAndImportsThroughAuthorizedProxy() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var requests=new CopyOnWriteArrayList<String>();
        var bodies=new CopyOnWriteArrayList<byte[]>();
        var authorization=new CopyOnWriteArrayList<String>();
        server.createContext("/internal/v1/audio-roles/models/", exchange->{
            requests.add(exchange.getRequestURI().toString());
            bodies.add(exchange.getRequestBody().readAllBytes());
            authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response="{\"state\":\"SUCCEEDED\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,response.length);
            try(var output=exchange.getResponseBody()){output.write(response);}
        });
        server.start();
        try {
            var settings=mock(ComputeNodeSettings.class);
            var connection=new Connection(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"isolated-test-token",List.of());
            when(settings.current()).thenReturn(connection);
            var client=new TranscodeClient(settings,new ObjectMapper());
            assertThat(client.call(connection,"/internal/v1/audio-roles/models/download?model=panns-cnn6","POST",null).path("state").asText()).isEqualTo("SUCCEEDED");
            byte[] body={1,2,3};
            assertThat(client.uploadAudioModel(new ByteArrayInputStream(body),body.length,"panns-cnn6").path("state").asText()).isEqualTo("SUCCEEDED");
            assertThat(requests).containsExactly("/internal/v1/audio-roles/models/download?model=panns-cnn6","/internal/v1/audio-roles/models/import?model=panns-cnn6");
            assertThat(bodies.get(1)).containsExactly(body);
            assertThat(authorization).containsOnly("Bearer isolated-test-token");
            assertThatThrownBy(()->client.call(connection,"/internal/v1/audio-roles/models/download?model=arbitrary","POST",null)).hasMessageContaining("无效的计算资源");
            assertThatThrownBy(()->client.uploadAudioModel(new ByteArrayInputStream(body),33L*1024*1024,"panns-cnn6")).hasMessageContaining("大小限制");
            assertThat(requests).hasSize(2);
        } finally {
            server.stop(0);
        }
    }
}
