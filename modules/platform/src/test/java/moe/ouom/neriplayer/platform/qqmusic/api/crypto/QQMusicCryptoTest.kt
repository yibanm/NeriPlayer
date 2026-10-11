package moe.ouom.neriplayer.platform.qqmusic.api.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

class QQMusicCryptoTest {

    @Test
    fun `blank skey falls back to default g_tk 5381`() {
        assertEquals(5381L, QQMusicCrypto.gTk(null))
        assertEquals(5381L, QQMusicCrypto.gTk(""))
    }

    @Test
    fun `g_tk folds skey through djb2 variant and masks to 31 bits`() {
        // h=5381; a(97): h += 5381*32+97 ; b(98): ... ; c(99): ... ; h & 0x7fffffff
        assertEquals(193485963L, QQMusicCrypto.gTk("abc"))
    }

    @Test
    fun `g_tk stays non-negative 31-bit even for long skey`() {
        val result = QQMusicCrypto.gTk("skey_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        assert(result in 0L..0x7FFFFFFFL)
    }
}
