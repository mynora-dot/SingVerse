package com.mynora.singverse.auth.repository;

import com.mynora.singverse.auth.recovery.AuthRecovery;
import com.mynora.singverse.infrastructure.database.DatabaseInitializer;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Repository;
import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.*;

@Repository
public class AuthStore {
    private final AuthMapper mapper;
    private final DataSource dataSource;
    private final Path root;
    private final Argon2PasswordEncoder passwords = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);

    public AuthStore(DataSource source, AuthMapper mapper, DatabaseInitializer database) {
        this.mapper = mapper;
        this.dataSource = source;
        root = database.root;
        if (mapper.countAccounts() == 0) {
            mapper.insertInitialAccount(passwords.encode("admin"));
        }
        AuthRecovery.apply(this);
    }

    public AuthMapper mapper() {
        return mapper;
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public Path root() {
        return root;
    }

    public Argon2PasswordEncoder passwords() {
        return passwords;
    }

    public Map<String, Object> account() {
        return mapper.account();
    }

    public long number(Map<String, Object> row, String field) {
        return ((Number) row.get(field)).longValue();
    }

    public void event(String type) {
        mapper.insertEvent(UUID.randomUUID().toString(), type, System.currentTimeMillis());
        mapper.deleteOldEvents(System.currentTimeMillis() - 90L * 86400000);
    }
}
