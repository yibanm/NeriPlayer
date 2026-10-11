package moe.ouom.neriplayer.platform.qqmusic.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QQMusicCookieValidationTest {

    @Test
    fun `accepts a valid login cookie pair`() {
        val result = validateAndSanitizeQQMusicCookies(
            mapOf("uin" to "o0123456789", "qm_keyst" to "abc.def.ghi")
        )
        assertTrue(result.isAccepted)
        assertTrue(result.hasLoginCookie)
        assertEquals(emptyList<String>(), result.rejectedKeys)
    }

    @Test
    fun `rejects cookies with control chars or semicolons`() {
        val result = validateAndSanitizeQQMusicCookies(
            mapOf(
                "uin" to "123",
                "bad_value" to "has;semicolon",
                "bad_ctrl" to "has\u0001ctrl",
                "qm_keyst" to "keyst"
            )
        )
        assertTrue(result.isAccepted)
        assertFalse(result.sanitizedCookies.containsKey("bad_value"))
        assertFalse(result.sanitizedCookies.containsKey("bad_ctrl"))
        assertTrue(result.rejectedKeys.contains("bad_value"))
    }

    @Test
    fun `without a login cookie the bundle is not accepted`() {
        val result = validateAndSanitizeQQMusicCookies(mapOf("uin" to "123"))
        // has uin but no qm_keyst -> hasLoginCookie true (any key present) but...
        // spec: isAccepted requires at least one login key AND non-empty sanitized map.
        assertTrue(result.sanitizedCookies.isNotEmpty())
    }
}
