package com.mynora.singverse.media;

/** Optional validation against the configured disposable MySQL schema. */
@org.springframework.test.context.ActiveProfiles(value = "mysql", inheritProfiles = false)
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class MySqlWebDavScanIT extends WebDavScanHttpTest {}
