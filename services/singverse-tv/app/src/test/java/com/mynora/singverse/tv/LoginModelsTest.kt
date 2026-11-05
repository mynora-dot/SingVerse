package com.mynora.singverse.tv

import org.junit.Assert.*
import org.junit.Test

class LoginModelsTest {
    @Test fun protocolsPortsAndIpv6() {
        assertEquals("http://192.168.1.10:18080", NasAddress.normalize("192.168.1.10:18080"))
        assertEquals("https://nas.local", NasAddress.normalize("nas.local:443", "https"))
        assertEquals("https://nas.local", NasAddress.normalize("HTTPS://NAS.local/"))
        assertEquals("http://[fd00::1]:18080", NasAddress.normalize("[fd00::1]:18080"))
        assertEquals("https", NasAddress.protocol("https://nas.local"))
    }

    @Test fun rejectsCredentialsPathsAndUnexpectedSchemes() {
        for (value in listOf("", "http://user:pass@nas", "http://nas/admin", "http://nas?x=1", "http://nas#x", "file:///nas", "nas:0", "nas:65536", "nas:abc", "http://nas:18080/../")) {
            assertThrows("accepted $value", Exception::class.java) { NasAddress.normalize(value) }
        }
    }

    @Test fun qrContainsApprovalDataWithoutPollCredential() {
        assertEquals("https://nas.local/admin/#/system/tv-devices?authorization=abc-123&code=012345", NasAddress.approvalUrl("https://nas.local", "abc-123", "012345"))
        assertThrows(IllegalArgumentException::class.java) { NasAddress.approvalUrl("http://nas", "id&other=1", "012345") }
        assertThrows(IllegalArgumentException::class.java) { NasAddress.approvalUrl("http://nas", "id", "12345") }
    }

    @Test fun recentServersMoveToFrontDeduplicateAndKeepAtMostTen() {
        val previous = (1..10).map { RecentNas("http://nas$it", "NAS $it", "admin", it.toLong()) }
        val updated = RecentNas("http://nas5", "新名字", "owner", 20L)
        val result = rememberNas(previous, updated)
        assertEquals(10, result.size); assertEquals(updated, result.first())
        assertEquals(1, result.count { it.address == "http://nas5" })
        assertEquals("http://nas9", rememberNas(result, RecentNas("https://new", "新 NAS", "", 30)).last().address)
    }
}
