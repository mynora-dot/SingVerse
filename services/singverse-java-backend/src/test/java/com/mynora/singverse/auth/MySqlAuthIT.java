package com.mynora.singverse.auth;

/** Repeat the real HTTP, transaction and cryptographic tests against MySQL. */
@org.springframework.test.context.ActiveProfiles(value = "mysql", inheritProfiles = false)
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class MySqlAuthIT extends AuthHttpTest {}
