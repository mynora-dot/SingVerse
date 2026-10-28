package com.mynora.singverse.auth.recovery;

import com.mynora.singverse.auth.repository.AuthStore;

import com.mynora.singverse.auth.service.AuthService;

import java.util.Arrays;

/** Local terminal only. No controller exposes this operation. */
public final class AuthRecovery {
    private static char[] password;

    private AuthRecovery() {}

    public static boolean active() {
        return password != null;
    }

    public static void prompt() {
        var console = System.console();
        if (console == null)
            throw new IllegalStateException("Recovery requires an interactive local terminal");
        console.printf(
                "Stop the backend first. This resets password, MFA, recovery codes and all"
                    + " sessions.%n");
        if (!"RESET".equals(console.readLine("Type RESET to continue: ")))
            throw new IllegalStateException("Recovery cancelled");
        char[] first = console.readPassword("New password (15-128 characters): ");
        char[] second = console.readPassword("Repeat new password: ");
        try {
            if (!Arrays.equals(first, second))
                throw new IllegalArgumentException("Passwords do not match");
            AuthService.validatePassword(new String(first));
            password = first.clone();
        } finally {
            if (first != null) Arrays.fill(first, '\0');
            if (second != null) Arrays.fill(second, '\0');
        }
    }

    public static void apply(AuthStore store) {
        if (!active()) return;
        try {
            String encoded = store.passwords().encode(new String(password));
            var manager =
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                            store.dataSource());
            new org.springframework.transaction.support.TransactionTemplate(manager)
                    .executeWithoutResult(
                            status -> {
                                store.mapper().recoverAccount(encoded);
                                store.mapper().deleteTokens();
                                store.mapper().deleteRecovery();
                                store.mapper().deleteThrottle();
                                store.event("local_account_recovery");
                            });
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
