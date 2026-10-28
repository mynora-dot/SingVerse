package com.mynora.singverse.media;

/** Run the same configuration, preview and optimistic concurrency assertions on a test MySQL schema. */
@org.springframework.test.context.ActiveProfiles(value = "mysql", inheritProfiles = false)
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class MySqlFilenameParserIT extends FilenameParserHttpTest {}
