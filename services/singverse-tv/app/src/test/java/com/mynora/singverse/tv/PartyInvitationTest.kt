package com.mynora.singverse.tv

import org.junit.Assert.*
import org.junit.Test

class PartyInvitationTest {
    @Test fun stableQrSurvivesPollingUntilItNeedsRenewal() {
        val request = PartyInvitation().begin(1000)!!
        assertNull(request.begin(2000))
        assertNull(request.begin(2000, force = true))
        val invitation = request.complete(request.generation, "http://nas/party/#invite=real-token", 3_601_000)
        assertNull(invitation.begin(10_000))
        assertNull(invitation.begin(3_540_999))
        val renewal = invitation.begin(3_541_000)!!
        assertTrue(renewal.loading)
        assertEquals("", renewal.url)
    }

    @Test fun failedRefreshNeverKeepsAnInvalidatedQrAndCanBeRetried() {
        val initial = PartyInvitation().begin(1000)!!
        val ready = initial.complete(initial.generation, "old-link", 3_601_000)
        val replacement = ready.begin(2000, force = true)!!
        assertEquals("", replacement.url)
        val failure = replacement.fail(replacement.generation, "网络异常", 3000)
        assertEquals("网络异常", failure.error)
        assertNull(failure.begin(32_999))
        assertNotNull(failure.begin(33_000))
        assertNotNull(failure.begin(4000, force = true))
    }

    @Test fun staleResultCannotRestoreQrAfterSessionOrServerChanges() {
        val request = PartyInvitation().begin(1000)!!
        val cleared = request.clear()
        assertEquals(cleared, cleared.complete(request.generation, "stale-link", 3_601_000))
        assertEquals(cleared, cleared.fail(request.generation, "stale-error", 2000))
        val next = cleared.begin(3000)!!
        assertEquals(next, next.complete(request.generation, "stale-link", 3_601_000))
        assertEquals("next-link", next.complete(next.generation, "next-link", 3_603_000).url)
    }

    @Test fun parsesServiceUtcExpiryOnApi23WithoutJavaTime() {
        assertEquals(0L, invitationExpiry("1970-01-01T00:00:00Z"))
        assertEquals(3_600_000L, invitationExpiry("1970-01-01T01:00:00.123456789Z"))
        for (value in listOf("", "1970-13-01T00:00:00Z", "1970-01-01T00:00:00", "1970-01-01T00:00:00Zjunk")) {
            assertThrows(Exception::class.java) { invitationExpiry(value) }
        }
    }
}
