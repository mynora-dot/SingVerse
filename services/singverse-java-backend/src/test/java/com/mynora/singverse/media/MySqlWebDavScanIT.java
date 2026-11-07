package com.mynora.singverse.media;

/** Optional validation against the configured disposable MySQL schema. */
@org.springframework.test.context.ActiveProfiles(value = "mysql", inheritProfiles = false)
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class MySqlWebDavScanIT extends WebDavScanHttpTest {
    // Other MySQL suites share this disposable schema but use their own temporary master key.
    @org.junit.jupiter.api.AfterAll
    static void clearEncryptedFixtures(@org.springframework.beans.factory.annotation.Autowired org.springframework.jdbc.core.JdbcTemplate database) {
        database.update("DELETE FROM media_song");
        database.update("DELETE FROM media_source");
        database.update("UPDATE compute_node_settings SET mode='AUTO',endpoint='',secret_encrypted=NULL,mappings_json='[]',revision=0");
    }
}
