package moe.ouom.neriplayer.platform.qqmusic.auth

import moe.ouom.neriplayer.data.model.qqmusic.auth.QQMusicAuthBundle
import org.json.JSONObject

fun QQMusicAuthBundle.toJson(): String {
    return JSONObject().apply {
        put(
            "cookies",
            JSONObject().apply {
                cookies.forEach { (key, value) -> put(key, value) }
            }
        )
        put("savedAt", savedAt)
    }.toString()
}

fun QQMusicAuthBundle.Companion.fromJson(json: String): QQMusicAuthBundle {
    return runCatching {
        val root = JSONObject(json)
        val cookiesJson = root.optJSONObject("cookies") ?: JSONObject()
        val cookies = linkedMapOf<String, String>()
        val keys = cookiesJson.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.isBlank()) continue
            cookies[key] = cookiesJson.optString(key, "")
        }
        val savedAt = root.optLong("savedAt", 0L)
        QQMusicAuthBundle(
            cookies = cookies,
            savedAt = savedAt
        ).normalized(savedAt = savedAt)
    }.getOrDefault(QQMusicAuthBundle())
}
