package moe.ouom.neriplayer.data.model.qqmusic.auth

/**
 * QQ Music 登录身份判定 Cookie。浏览器登录态里同时出现 uin（数字 QQ 号）与 qm_keyst。
 */
val QQ_LOGIN_COOKIE_KEYS = listOf("uin", "qm_keyst")

data class QQMusicAuthBundle(
    val cookies: Map<String, String> = emptyMap(),
    val savedAt: Long = 0L
) {
    fun hasLoginCookies(): Boolean =
        QQ_LOGIN_COOKIE_KEYS.any { key -> !cookies[key].isNullOrBlank() }

    fun normalized(savedAt: Long = this.savedAt): QQMusicAuthBundle = copy(
        cookies = LinkedHashMap(cookies.filterKeys { it.isNotBlank() }),
        savedAt = savedAt
    )

    companion object
}
