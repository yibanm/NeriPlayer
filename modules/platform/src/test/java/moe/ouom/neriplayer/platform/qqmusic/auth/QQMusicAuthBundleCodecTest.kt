package moe.ouom.neriplayer.platform.qqmusic.auth

import moe.ouom.neriplayer.data.model.qqmusic.auth.QQMusicAuthBundle
import org.junit.Assert.assertEquals
import org.junit.Test

class QQMusicAuthBundleCodecTest {

    @Test
    fun `json round trip keeps cookies and saved time`() {
        val bundle = QQMusicAuthBundle(
            cookies = linkedMapOf("uin" to "123456789", "qm_keyst" to "secret"),
            savedAt = 42L
        )

        assertEquals(bundle, QQMusicAuthBundle.fromJson(bundle.toJson()))
    }

    @Test
    fun `json decoding drops blank cookie names and tolerants missing fields`() {
        assertEquals(
            QQMusicAuthBundle(cookies = linkedMapOf("uin" to "123"), savedAt = 0L),
            QQMusicAuthBundle.fromJson("""{"cookies":{" ":"orphan","uin":"123"}}""")
        )
        assertEquals(
            QQMusicAuthBundle(savedAt = 9L),
            QQMusicAuthBundle.fromJson("""{"cookies":"not-an-object","savedAt":9}""")
        )
    }

    @Test
    fun `unreadable json restores an empty bundle`() {
        assertEquals(QQMusicAuthBundle(), QQMusicAuthBundle.fromJson("not json"))
    }
}
