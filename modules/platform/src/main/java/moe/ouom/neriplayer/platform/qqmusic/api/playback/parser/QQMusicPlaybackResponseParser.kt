package moe.ouom.neriplayer.platform.qqmusic.api.playback.parser

import org.json.JSONArray
import org.json.JSONObject

/**
 * 解析 musicu.fcg `vkey.GetVkeyServer / CgiGetVkey` 响应，避免把 JSONObject.NULL 当成字符串 "null"。
 *
 * 响应结构（节选）：
 * ```
 * {
 *   "req":     { "data": { "sip": ["https://dl.stream.qqmusic.qq.com/", ...] } },
 *   "req_0":   { "code": 0,
 *                "data": { "midurlinfo": [ { "purl": "F000xxx.flac?vkey=...", "songmid": "..." } ] } }
 * }
 * ```
 * 最终可播 URL = sip[0]（缺失时用默认 CDN host）+ purl。
 */
object QQMusicPlaybackResponseParser {

    const val DEFAULT_CDN_HOST: String = "https://dl.stream.qqmusic.qq.com"

    sealed class PlaybackResult {
        data class Success(
            val url: String,
            val type: String? = null,
            val notice: Notice? = null,
            val songMid: String? = null,
            val purl: String? = null
        ) : PlaybackResult()

        object RequiresLogin : PlaybackResult()

        data class Failure(val reason: FailureReason = FailureReason.UNKNOWN) : PlaybackResult()
    }

    enum class Notice {
        PREVIEW_CLIP
    }

    enum class FailureReason {
        NO_PERMISSION,
        NO_PLAY_URL,
        UNKNOWN
    }

    fun parseVkeyResponse(raw: String, defaultCdnHost: String = DEFAULT_CDN_HOST): PlaybackResult {
        val root = runCatching { JSONObject(raw) }.getOrElse {
            return PlaybackResult.Failure(FailureReason.UNKNOWN)
        }
        val req0 = root.optJSONObject("req_0")
            ?: return PlaybackResult.Failure(FailureReason.NO_PLAY_URL)

        when (req0.optInt("code", -1)) {
            // QQ 侧常见的需要登录/鉴权错误码
            -100, -110, -120 -> return PlaybackResult.RequiresLogin
        }

        val data = req0.optJSONObject("data")
            ?: return PlaybackResult.Failure(FailureReason.NO_PLAY_URL)
        val midUrlInfo = data.optJSONArray("midurlinfo")
            ?: return PlaybackResult.Failure(FailureReason.NO_PLAY_URL)
        val first = midUrlInfo.optJSONObject(0)
            ?: return PlaybackResult.Failure(FailureReason.NO_PLAY_URL)

        val purl = first.optCleanString("purl")
            ?: return PlaybackResult.Failure(classifyNoPurl(first))
        val sip = extractSip(root)
        val host = (sip.firstOrNull { it.isNotBlank() } ?: defaultCdnHost).trimEnd('/')
        val url = if (purl.startsWith("http")) {
            purl
        } else {
            host + "/" + purl.removePrefix("/")
        }
        return PlaybackResult.Success(
            url = url,
            type = detectType(purl),
            notice = detectNotice(first),
            songMid = first.optCleanString("songmid"),
            purl = purl
        )
    }

    private fun classifyNoPurl(entry: JSONObject): FailureReason {
        val status = entry.optInt("status", 0)
        return if (status != 0) FailureReason.NO_PERMISSION else FailureReason.NO_PLAY_URL
    }

    private fun detectNotice(entry: JSONObject): Notice? {
        // preview 试听片段在 midurlinfo 中以略短时长标记，这里仅做存在性兜底
        val raw = entry.opt("preview")
        val present = raw != null && raw != JSONObject.NULL
        return if (present) Notice.PREVIEW_CLIP else null
    }

    private fun detectType(purl: String): String? = when {
        purl.contains(".flac", ignoreCase = true) -> "flac"
        purl.contains(".mp3", ignoreCase = true) -> "mp3"
        purl.contains(".m4a", ignoreCase = true) -> "m4a"
        purl.contains(".ogg", ignoreCase = true) -> "ogg"
        else -> null
    }

    private fun extractSip(root: JSONObject): List<String> {
        val req = root.optJSONObject("req") ?: return emptyList()
        val data = req.optJSONObject("data") ?: return emptyList()
        val sip = data.optJSONArray("sip") ?: return emptyList()
        return buildList {
            for (i in 0 until sip.length()) {
                sip.optCleanString(i)?.let(::add)
            }
        }
    }

    private fun JSONObject.optCleanString(key: String): String? {
        val raw = opt(key)
        val value = if (raw == null || raw == JSONObject.NULL) {
            null
        } else if (raw is String) {
            raw.trim()
        } else {
            raw.toString().trim()
        }
        return value?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
    }

    private fun JSONArray.optCleanString(index: Int): String? {
        if (index < 0 || index >= length()) return null
        val raw = opt(index)
        val value = if (raw == null || raw == JSONObject.NULL) {
            null
        } else if (raw is String) {
            raw.trim()
        } else {
            raw.toString().trim()
        }
        return value?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
    }
}
