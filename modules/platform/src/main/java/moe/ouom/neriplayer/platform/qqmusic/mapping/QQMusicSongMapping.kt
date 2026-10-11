package moe.ouom.neriplayer.platform.qqmusic.mapping

import org.json.JSONObject

/** 从 getUserInfo() 原始 JSON 提取的轻量用户画像。 */
data class QQMusicUserProfile(
    val uin: String,
    val nickname: String,
    val avatarUrl: String
)

/**
 * 解析 music.userinfo.UserInfo 响应。字段缺失时给空串，绝不抛异常。
 */
fun parseQQMusicUserInfo(raw: String): QQMusicUserProfile? {
    return runCatching {
        val root = JSONObject(raw)
        val req0 = root.optJSONObject("req_0") ?: return@runCatching null
        if (req0.optInt("code", -1) != 0) return@runCatching null
        val data = req0.optJSONObject("data") ?: return@runCatching null
        val vuser = data.optJSONObject("vuser") ?: return@runCatching null
        QQMusicUserProfile(
            uin = data.optString("uin", vuser.optString("encrypt_uin", "")),
            nickname = vuser.optString("nick", "").ifBlank { vuser.optString("name", "") },
            avatarUrl = vuser.optString("headPic", "").ifBlank { vuser.optString("face", "") }
        )
    }.getOrNull()
}
