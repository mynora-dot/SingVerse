package com.mynora.singverse.compute;

import static org.assertj.core.api.Assertions.*;
import com.mynora.singverse.auth.security.AuthHttp;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

class ComputeCallbackTest {
    @TempDir Path temp;
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    void admin() { SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "admin", null, java.util.List.of(new SimpleGrantedAuthority("ADMIN")))); }
    MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("POST", "/api/v1/compute-node/test");
        request.setRemoteAddr("127.0.0.1");request.setScheme("http");request.addHeader("Host","10.0.0.2:18080");
        request.addHeader("X-Forwarded-Host","evil.example");request.addHeader("X-Forwarded-Proto","https");return request;
    }
    @Test void onlyAdminMayEstablishOriginAndUntrustedForwardingIsIgnored() {
        var env=new MockEnvironment();var callback=new ComputeCallback(new AuthHttp(env),env);
        callback.preHandle(request(),new MockHttpServletResponse(),null);
        assertThatThrownBy(callback::origin).isInstanceOf(ResponseStatusException.class);
        admin();callback.preHandle(request(),new MockHttpServletResponse(),null);
        assertThat(callback.origin()).isEqualTo("http://10.0.0.2:18080");
    }
    @Test void deploymentOriginWinsAndOnlyTrustedProxiesMaySupplyForwarding() {
        var env=new MockEnvironment().withProperty("singverse.auth.trusted-proxies","127.0.0.1/32");
        var callback=new ComputeCallback(new AuthHttp(env),env);admin();callback.preHandle(request(),new MockHttpServletResponse(),null);
        assertThat(callback.origin()).isEqualTo("https://evil.example");
        env.withProperty("singverse.compute.callback-origin","http://10.0.0.3:8080");
        callback=new ComputeCallback(new AuthHttp(env),env);callback.preHandle(request(),new MockHttpServletResponse(),null);
        assertThat(callback.origin()).isEqualTo("http://10.0.0.3:8080");
    }
    @Test void composeCallbackIsUsedOnlyForItsMatchingAutomaticNode() {
        var env=new MockEnvironment().withProperty("singverse.compute.base-url","http://singverse-compute:8090")
                .withProperty("singverse.compute.auto-callback-origin","http://singverse-java-backend:8080");
        var callback=new ComputeCallback(new AuthHttp(env),env);
        var automatic=new ComputeNodeDtos.Connection(java.net.URI.create("http://singverse-compute:8090"),"fixture",java.util.List.of());
        var remote=new ComputeNodeDtos.Connection(java.net.URI.create("http://10.100.1.12:18090"),"fixture",java.util.List.of());
        assertThat(callback.origin(automatic)).isEqualTo("http://singverse-java-backend:8080");
        assertThatThrownBy(()->callback.origin(remote)).isInstanceOf(ResponseStatusException.class);
        admin();callback.preHandle(request(),new MockHttpServletResponse(),null);
        assertThat(callback.origin(remote)).isEqualTo("http://10.0.0.2:18080");
        assertThat(callback.origin(automatic)).isEqualTo("http://singverse-java-backend:8080");
        env.withProperty("singverse.compute.callback-origin","http://10.0.0.3:18080");
        var configured=new ComputeCallback(new AuthHttp(env),env);
        assertThat(configured.origin(automatic)).isEqualTo("http://10.0.0.3:18080");
        assertThat(configured.origin(remote)).isEqualTo("http://10.0.0.3:18080");
    }
    @Test void macNativeCallbackDoesNotLeakToAnIndependentNode() {
        var env=new MockEnvironment().withProperty("singverse.compute.base-url","http://host.docker.internal:18090")
                .withProperty("singverse.compute.auto-callback-origin","http://127.0.0.1:18080");
        var callback=new ComputeCallback(new AuthHttp(env),env);
        admin();callback.preHandle(request(),new MockHttpServletResponse(),null);
        assertThat(callback.origin(new ComputeNodeDtos.Connection(java.net.URI.create("http://host.docker.internal:18090"),"fixture",java.util.List.of())))
                .isEqualTo("http://127.0.0.1:18080");
        assertThat(callback.origin(new ComputeNodeDtos.Connection(java.net.URI.create("http://10.100.1.12:18090"),"fixture",java.util.List.of())))
                .isEqualTo("http://10.0.0.2:18080");
    }
    @Test void callbackProbeNeedsMatchingBearerAndActiveGrant() {
        var env=new MockEnvironment();var callback=new ComputeCallback(new AuthHttp(env),env);
        String id=callback.authorize("secret");
        assertThat(callback.verify(id,"Bearer secret")).isEqualTo(id);
        assertThatThrownBy(()->callback.verify(id,"Bearer other")).isInstanceOf(ResponseStatusException.class);
        callback.revoke(id);assertThatThrownBy(()->callback.verify(id,"Bearer secret")).isInstanceOf(ResponseStatusException.class);
    }
    @Test void localMetadataRangesAreBoundedAndRevokeWhenFileChanges() throws Exception {
        var input=new LocalMetadataInput();Path file=temp.resolve("song.mp3");Files.writeString(file,"abcdefghij");
        String id=input.authorize(file,"secret");var response=new MockHttpServletResponse();
        input.read(id,"Bearer secret","bytes=2-5",false,response);
        assertThat(response.getStatus()).isEqualTo(206);assertThat(response.getContentAsString()).isEqualTo("cdef");
        assertThat(response.getHeader("Content-Range")).isEqualTo("bytes 2-5/10");
        response=new MockHttpServletResponse();input.read(id,"Bearer secret","bytes=0-1",true,response);
        assertThat(response.getContentAsByteArray()).isEmpty();assertThat(response.getHeader("Content-Length")).isEqualTo("2");
        assertThatThrownBy(()->input.read(id,"Bearer wrong","bytes=0-1",false,new MockHttpServletResponse())).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->input.read(id,"Bearer secret","bytes=0-1048576",false,new MockHttpServletResponse())).isInstanceOf(ResponseStatusException.class);
        Files.writeString(file,"changed-media");
        assertThatThrownBy(()->input.read(id,"Bearer secret","bytes=0-1",false,new MockHttpServletResponse())).isInstanceOf(ResponseStatusException.class);
        String next=input.authorize(file,"secret");input.revoke(next);
        assertThatThrownBy(()->input.read(next,"Bearer secret","bytes=0-1",false,new MockHttpServletResponse())).isInstanceOf(ResponseStatusException.class);
    }
    @Test void replacingAuthorizedFileWithSymlinkIsRejected() throws Exception {
        var input=new LocalMetadataInput();Path file=temp.resolve("source"),other=temp.resolve("other");
        Files.writeString(file,"original");Files.writeString(other,"private");String id=input.authorize(file,"secret");
        Files.delete(file);Files.createSymbolicLink(file,other);
        assertThatThrownBy(()->input.read(id,"Bearer secret","bytes=0-1",false,new MockHttpServletResponse())).isInstanceOf(ResponseStatusException.class);
    }
}
