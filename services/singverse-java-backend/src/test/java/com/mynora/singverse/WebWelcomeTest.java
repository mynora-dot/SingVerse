package com.mynora.singverse;

import java.nio.file.Path;
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
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class WebWelcomeTest {
    @TempDir static Path temp;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("singverse.transcoding.cache-directory", () -> temp.resolve("cache").toString());
        registry.add("singverse.transcoding.directory", () -> temp.resolve("versions").toString());
        registry.add("singverse.audio.directory", () -> temp.resolve("audio").toString());
        registry.add("singverse.auth.key-file", () -> temp.resolve("auth.key").toString());
        registry.add("singverse.metadata.cache-path", () -> temp.resolve("covers").toString());
        registry.add("singverse.webdav.metadata-path", () -> temp.resolve("webdav").toString());
        registry.add("singverse.sqlite.path", () -> temp.resolve("web-test.db").toString());
    }

    @Autowired TestRestTemplate http;

    @Test void partyServesTheMobileApplication() {
        var response = http.getForEntity("/party/", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML)).isTrue();
        assertThat(response.getBody()).contains("SingVerse · 一起点歌", "id=\"app\"", "name=\"viewport\"");
        var head = http.exchange("/party/", HttpMethod.HEAD, null, String.class);
        assertThat(head.getStatusCode().value()).isEqualTo(200);
        assertThat(head.getBody()).isNull();
    }

    @Test void stylesheetIsServedFromTheSameApplication() {
        var html = http.getForObject("/party/", String.class);
        var stylesheet = java.util.regex.Pattern.compile("href=\"([^\"]+\\.css)\"").matcher(html);
        assertThat(stylesheet.find()).isTrue();
        var path = java.net.URI.create("/party/").resolve(stylesheet.group(1)).toString();
        var response = http.getForEntity(path, String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.valueOf("text/css"))).isTrue();
        assertThat(response.getBody()).isNotBlank();
    }
}
