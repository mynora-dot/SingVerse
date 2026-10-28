package com.mynora.singverse;

import java.nio.file.Path;
import java.net.URI;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("sqlite")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminWebTest {
    @TempDir static Path temp;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("singverse.sqlite.path", () -> temp.resolve("admin-test.db").toString());
    }

    @Autowired TestRestTemplate http;

    @Test void directoryAndExplicitIndexServeTheBuiltAdmin() {
        for (var path : new String[]{"/", "/admin", "/admin/", "/admin/index.html"}) {
            var response = http.getForEntity(path, String.class);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getHeaders().getContentType()).isNotNull();
            assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML)).isTrue();
            assertThat(response.getBody()).contains("SingVerse · NAS 管理中心", "id=\"app\"");
            assertThat(response.getBody()).doesNotContain("/src/main.ts", "http://localhost:5173");
        }
        var head = http.exchange("/admin/", HttpMethod.HEAD, null, String.class);
        assertThat(head.getStatusCode().value()).isEqualTo(200);
        assertThat(head.getBody()).isNull();
    }

    @Test void everyReferencedScriptAndStylesheetIsServedLocally() {
        var html = http.getForObject("/admin/", String.class);
        var matcher = Pattern.compile("(?:src|href)=\"([^\"]+\\.(?:js|css))\"").matcher(html);
        int count = 0;
        while (matcher.find()) {
            var reference = URI.create(matcher.group(1));
            assertThat(reference.isAbsolute()).isFalse();
            assertThat(reference.getAuthority()).isNull();
            var asset = URI.create("/admin/").resolve(reference).toString();
            assertThat(asset).startsWith("/assets/");
            var response = http.getForEntity(asset, String.class);
            assertThat(response.getStatusCode().value()).as(asset).isEqualTo(200);
            assertThat(response.getBody()).isNotBlank().doesNotContain("<!doctype html>");
            var contentType = response.getHeaders().getContentType();
            assertThat(contentType).isNotNull();
            if (asset.endsWith(".css")) {
                assertThat(contentType.isCompatibleWith(MediaType.valueOf("text/css"))).isTrue();
            } else {
                assertThat(contentType.toString()).contains("javascript");
            }
            count++;
        }
        assertThat(count).isGreaterThanOrEqualTo(2);
        assertThat(http.getForEntity("/assets/missing.js", String.class).getStatusCode().value()).isEqualTo(404);
        assertThat(http.getForEntity("/api/v1/missing", String.class).getStatusCode().value()).isEqualTo(401);
    }
}
