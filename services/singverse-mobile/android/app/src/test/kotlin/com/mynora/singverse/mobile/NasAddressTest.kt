package com.mynora.singverse.mobile

import org.junit.Assert.*
import org.junit.Test

class NasAddressTest {
    @Test fun mobileClientFamilyIsAcceptedByComputeWireContract() {
        val contract = java.io.File(requireNotNull(System.getProperty("singverse.computeContract"))).readText()
        val family = Regex("\\\"family\\\"\\s*:\\s*\\{[\\s\\S]*?\\\"enum\\\"\\s*:\\s*\\[([^]]*)]")
            .find(contract) ?: error("Compute ClientInput.family enum is missing")
        val allowed = Regex("\\\"([^\\\"]+)\\\"").findAll(family.groupValues[1]).map { it.groupValues[1] }.toSet()
        assertTrue("Mobile family must be an accepted compute wire value", PlaybackProtocol.CLIENT_FAMILY in allowed)
    }
    @Test fun originNormalizationPreservesPortAndRejectsCredentialsAndPaths() {
        assertEquals("http://nas.local:18080", NasTransport.normalize("http://NAS.local:18080/"))
        assertEquals("https://nas.local", NasTransport.normalize("https://nas.local:443"))
        assertEquals("http://[::1]:18080", NasTransport.normalize("http://[::1]:18080"))
        for (address in listOf("file:///private/file", "http://admin:password@nas.local", "https://nas.local/admin/", "https://nas.local/?secret=value")) {
            assertThrows(NasFailure::class.java) { NasTransport.normalize(address) }
        }
    }
    @Test fun everyMediaResourceMustRemainInCurrentNasApi() {
        NasTransport.checkResource("https://nas.local", "https://nas.local/api/v1/transcoding/sessions/id/artifacts/master.m3u8")
        for (resource in listOf("https://other.local/api/segment", "http://nas.local/api/segment", "https://nas.local:8443/api/segment", "https://nas.local/admin/", "https://user@nas.local/api/segment", "https://nas.local/api/../admin/", "https://nas.local/api/%2e%2e/admin/", "file:///api/private", "https://nas.local/api/init_%v.mp4")) {
            assertThrows(NasFailure::class.java) { NasTransport.checkResource("https://nas.local", resource) }
        }
    }
}
