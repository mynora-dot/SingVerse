package com.mynora.singverse.auth.repository;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** SQL is defined in the corresponding classpath mapper XML. */
@Mapper
public interface AuthMapper {
    int setCsrf(@Param("csrf") String csrf, @Param("tokenHash") String tokenHash);
    int countRecovery();
    int deleteExpiredTokens(
            @Param("expiresBefore") long expiresBefore,
            @Param("lastSeenBefore") long lastSeenBefore);
    int insertToken(
            @Param("tokenHash") String tokenHash,
            @Param("kind") String kind,
            @Param("csrf") String csrf,
            @Param("createdAt") long createdAt,
            @Param("lastSeen") long lastSeen,
            @Param("expiresAt") long expiresAt,
            @Param("revision") long revision);
    List<Map<String, Object>> findToken(
            @Param("tokenHash") String tokenHash,
            @Param("kind") String kind,
            @Param("expiresAfter") long expiresAfter,
            @Param("lastSeenAfter") long lastSeenAfter,
            @Param("revision") long revision);
    int touchToken(@Param("lastSeen") long lastSeen, @Param("tokenHash") String tokenHash);
    int deleteToken(@Param("tokenHash") String tokenHash);
    List<Map<String, Object>> findThrottle(@Param("bucket") String bucket);
    int insertThrottle(
            @Param("bucket") String bucket,
            @Param("failures") int failures,
            @Param("blockedUntil") long blockedUntil,
            @Param("updatedAt") long updatedAt);
    int updateThrottle(
            @Param("failures") int failures,
            @Param("blockedUntil") long blockedUntil,
            @Param("updatedAt") long updatedAt,
            @Param("bucket") String bucket);
    int clearLimits(
            @Param("accountBucket") String accountBucket,
            @Param("addressBucket") String addressBucket);
    int acceptTotpStep(
            @Param("step") long step,
            @Param("previousStepLimit") long previousStepLimit,
            @Param("revision") long revision);
    int consumeRecovery(@Param("codeHash") String codeHash);
    int incrementTokenAttempts(@Param("tokenHash") String tokenHash);
    int tokenAttempts(@Param("tokenHash") String tokenHash);
    int startMfaSetup(@Param("secret") String secret, @Param("expiresAt") long expiresAt);
    int cancelMfaSetup();
    int incrementSetupAttempts();
    int confirmMfa(@Param("step") long step);
    int deleteRecovery();
    int insertRecovery(@Param("codeHash") String codeHash);
    int disableMfa();
    int changePassword(@Param("passwordHash") String passwordHash);
    int invalidateAccount();
    int deleteTokens();
    int countAccounts();
    int insertInitialAccount(@Param("passwordHash") String passwordHash);
    Map<String, Object> account();
    int insertEvent(
            @Param("id") String id,
            @Param("eventType") String eventType,
            @Param("occurredAt") long occurredAt);
    int deleteOldEvents(@Param("before") long before);
    int recoverAccount(@Param("passwordHash") String passwordHash);
    int deleteThrottle();
}
