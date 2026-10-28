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
class WebWelcomeTest {
    @TempDir static Path temp;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("singverse.sqlite.path", () -> temp.resolve("web-test.db").toString());
    }

    @Autowired TestRestTemplate http;

    @Test void partyServesTheMobileWelcomePage() {
        var response = http.getForEntity("/party/", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML)).isTrue();
        assertThat(response.getBody()).contains("<title>SingVerse</title>", "欢迎来到家庭 KTV", "name=\"viewport\"");
        var head = http.exchange("/party/", HttpMethod.HEAD, null, String.class);
        assertThat(head.getStatusCode().value()).isEqualTo(200);
        assertThat(head.getBody()).isNull();
    }

    @Test void stylesheetIsServedFromTheSameApplication() {
        var response = http.getForEntity("/party/assets/welcome.css", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.valueOf("text/css"))).isTrue();
        assertThat(response.getBody()).isNotBlank();
    }
}
