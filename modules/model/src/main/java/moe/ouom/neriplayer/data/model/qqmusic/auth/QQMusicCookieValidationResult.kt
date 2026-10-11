package moe.ouom.neriplayer.data.model.qqmusic.auth

data class QQMusicCookieValidationResult(
    val sanitizedCookies: Map<String, String> = emptyMap(),
    val rejectedKeys: List<String> = emptyList()
) {
    val hasLoginCookie: Boolean
        get() = QQ_LOGIN_COOKIE_KEYS.any { key -> !sanitizedCookies[key].isNullOrBlank() }

    val isAccepted: Boolean
        get() = sanitizedCookies.isNotEmpty() && hasLoginCookie
}
