package moe.ouom.neriplayer.platform.qqmusic.api.crypto

/**
 * QQ 音乐 musicu.fcg 接口的 g_tk 签名。
 *
 * 算法与网页端一致：h = 5381；对 skey 中每个字符 c：h += (h << 5) + c；最后取低 31 位。
 * 未登录（无 skey）时回退到公开值 5381。
 */
object QQMusicCrypto {

    const val DEFAULT_GT_K: Long = 5381L

    fun gTk(skey: String?): Long {
        if (skey.isNullOrBlank()) return DEFAULT_GT_K
        var h = 5381L
        for (c in skey) {
            h += (h shl 5) + c.code
        }
        return h and MASK
    }

    private const val MASK = 0x7FFFFFFFL
}
