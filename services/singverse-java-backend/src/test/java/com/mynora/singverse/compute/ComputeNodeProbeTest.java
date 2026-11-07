package com.mynora.singverse.compute;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mynora.singverse.compute.ComputeNodeDtos.Connection;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ComputeNodeProbeTest {
    final ComputeNodeProbe probe=new ComputeNodeProbe(new ObjectMapper());
    final String token="isolated-node-probe-token-0000000000";
    @Test void absentNodeAndCredentialsDoNotIssueNetworkRequests() {
        assertThat(probe.check(null).status()).isEqualTo("DISABLED");
        assertThat(probe.check(new Connection(URI.create("http://127.0.0.1:1"),"",List.of())).status()).isEqualTo("MISSING_TOKEN");
    }
    @Test void authenticatedStatusIsTheOnlyCapabilityProbe() throws Exception {
        withNode(200,"{\"service\":\"singverse-compute\",\"apiVersion\":\"1\",\"capabilities\":[\"media-remote-tags\"]}",connection->{
            var result=probe.check(connection);assertThat(result.status()).isEqualTo("AVAILABLE");
            assertThat(result.capabilities()).containsExactly("media-remote-tags");
        });
    }
    @Test void malformedOversizedAndUnauthenticatedResponsesNeverBecomeCapabilities() throws Exception {
        for(String body:List.of("not json","null","{}",
                "{\"service\":\"singverse-compute\",\"apiVersion\":\"2\",\"capabilities\":[]}",
                "{\"service\":\"singverse-compute\",\"apiVersion\":\"1\",\"capabilities\":[null]}","x".repeat(65537)))
            withNode(200,body,connection->{var result=probe.check(connection);assertThat(result.status()).isNotEqualTo("AVAILABLE");assertThat(result.capabilities()).isEmpty();});
        withNode(401,"{}",connection->assertThat(probe.check(connection).status()).isEqualTo("AUTH_FAILED"));
        withNode(302,"{}",connection->assertThat(probe.check(connection).status()).isEqualTo("UNAVAILABLE"));
    }
    private void withNode(int status,String body,java.util.function.Consumer<Connection> check)throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/internal/v1/status",exchange->{
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer "+token);
            byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status,bytes.length);
            try(var out=exchange.getResponseBody()){out.write(bytes);}
        });
        server.start();
        try{check.accept(new Connection(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),token,List.of()));}
        finally{server.stop(0);}
    }
}
