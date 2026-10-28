package com.mynora.singverse.infrastructure.database;

import java.util.Arrays;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;

import org.springframework.stereotype.Component;

@Component
public class DatabaseConnectionCheck implements ApplicationRunner {
    private final DatabaseProbeMapper mapper;
    private final Environment environment;

    public DatabaseConnectionCheck(DatabaseProbeMapper mapper, Environment environment) {
        this.mapper = mapper;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        var profiles = Arrays.asList(environment.getActiveProfiles());
        if (profiles.contains("sqlite") && profiles.contains("mysql")) {
            throw new IllegalStateException("Select only one database profile: sqlite or mysql");
        }
        if (!Integer.valueOf(1).equals(mapper.check())) {
            throw new IllegalStateException("Database connection verification failed");
        }
    }
}
