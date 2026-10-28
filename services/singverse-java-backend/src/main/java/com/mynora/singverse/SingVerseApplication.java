package com.mynora.singverse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(
        exclude =
                org.springframework.boot.autoconfigure.security.servlet
                        .UserDetailsServiceAutoConfiguration.class)
public class SingVerseApplication {
    public static void main(String[] args) {
        if (java.util.Arrays.asList(args).contains("--reset-admin")) {
            com.mynora.singverse.auth.recovery.AuthRecovery.prompt();
            var application = new SpringApplication(SingVerseApplication.class);
            application.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
            try (var context = application.run(args)) {
                System.out.println(
                        "Administrator recovered. Restart the backend and sign in with the new"
                            + " password.");
            }
        } else {
            SpringApplication.run(SingVerseApplication.class, args);
        }
    }
}
