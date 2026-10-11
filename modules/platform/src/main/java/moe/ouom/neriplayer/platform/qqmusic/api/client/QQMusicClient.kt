package moe.ouom.neriplayer.platform.qqmusic.api.client

import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.platform.qqmusic.api.crypto.QQMusicCrypto
import moe.ouom.neriplayer.platform.qqmusic.api.playback.parser.QQMusicPlaybackResponseParser
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * QQ 音乐已鉴权 API 客户端，自持有 Cookie 会话（与 NeteaseClient 一致），
 * 统一走 `u.y.qq.com/cgi-bin/musicu.fcg` 这个网关。
 *
 * 搜索与歌词仍复用匿名 [moe.ouom.neriplayer.platform.search.api.client.QQMusicSearchApi]，
 * 本客户端只负责：登录态维持、播放直链（vkey）、用户歌单、歌手信息。
 */
class QQMusicClient(
    private val client: OkHttpClient,
    private val debugLogging: Boolean = false
) {

    private val sessionLock = Any()
    private val persistedCookies: MutableMap<String, String> = linkedMapOf()

    /** 设备 guid，vkey 接口强依赖；进程内稳定随机值。 */
    val guid: String = generateGuid()

    fun hasLogin(): Boolean = synchronized(sessionLock) {
        val uin = persistedCookies["uin"].orEmpty().trim()
        val keyst = persistedCookies["qm_keyst"].orEmpty().trim()
        uin.isNotBlank() && keyst.isNotBlank()
    }

    fun setPersistedCookies(cookies: Map<String, String>) = synchronized(sessionLock) {
        persistedCookies.clear()
        cookies.forEach { (key, value) ->
            val name = key.trim()
            if (name.isNotBlank()) persistedCookies[name] = value.trim()
        }
    }

    fun getCookies(): Map<String, String> = synchronized(sessionLock) {
        LinkedHashMap(persistedCookies)
    }

    fun logout() = synchronized(sessionLock) {
        persistedCookies.clear()
    }

    /** 当前登录 uin（纯数字字符串），未登录返回 "0"。 */
    private fun currentUin(): String = synchronized(sessionLock) {
        val raw = persistedCookies["uin"].orEmpty().trim()
        // uin Cookie 形如 "o0123456789"，去掉前导 o
        raw.removePrefix("o").trimStart('0').ifBlank { "0" }.let { parsed ->
            parsed.toLongOrNull()?.toString() ?: "0"
        }
    }

    private fun skey(): String? = synchronized(sessionLock) {
        persistedCookies["skey"]?.takeIf { it.isNotBlank() }
    }

    // ---------------------------------------------------------------------------------------------
    // 业务接口
    // ---------------------------------------------------------------------------------------------

    /** 用户信息（musicu.fcg userinfo）。返回原始 JSON。 */
    fun getUserInfo(): String {
        val envelope = JSONObject().apply {
            put("req_0", JSONObject().apply {
                put("module", "music.userinfo.UserInfo")
                put("method", "GetUserInfo")
                put("param", JSONObject())
            })
            put("comm", buildComm())
        }
        return request(envelope)
    }

    /** 用户创建/订阅的歌单列表（remote playlists）。返回原始 JSON。 */
    fun getUserPlaylists(uin: String): String {
        val envelope = JSONObject().apply {
            put("req_1", JSONObject().apply {
                put("module", "music.srfDissInfoDiss")
                put("method", "GetDissList")
                put("param", JSONObject().apply {
                    put("uin", uin)
                    put("cmd", 1)
                    put("page", 0)
                    put("per_page", 100)
                    put("syntax", 0)
                    put("sort", 1)
                    put("only_song", 0)
                    put("gzip", 0)
                })
            })
            put("comm", buildComm())
        }
        return request(envelope)
    }

    /** 单个歌单详情（含曲目列表）。dissid 为字符串 ID。返回原始 JSON。 */
    fun getPlaylistDetail(dissid: String): String {
        val envelope = JSONObject().apply {
            put("req_0", JSONObject().apply {
                put("module", "music.srfDissInfoDiss")
                put("method", "GetDissInfo")
                put("param", JSONObject().apply {
                    put("disstid", dissid)
                    put("onlysong", 0)
                    put("song_num", 0)
                })
            })
            put("comm", buildComm())
        }
        return request(envelope)
    }

    /** 歌手详情（头像、简介等）。artistMid 为字符串 singermid。返回原始 JSON。 */
    fun getArtistDetail(artistMid: String): String {
        val envelope = JSONObject().apply {
            put("req_0", JSONObject().apply {
                put("module", "music.SingerDetail")
                put("method", "GetSingerDetail")
                put("param", JSONObject().apply {
                    put("singermid", artistMid)
                })
            })
            put("comm", buildComm())
        }
        return request(envelope)
    }

    /** 歌手热门曲目（top songs）。返回原始 JSON。 */
    fun getArtistSongs(artistMid: String, begin: Int = 0, num: Int = 50): String {
        val envelope = JSONObject().apply {
            put("req_1", JSONObject().apply {
                put("module", "music.singerSongApi")
                put("method", "GetSingerSong")
                put("param", JSONObject().apply {
                    put("singermid", artistMid)
                    put("order", "listen")
                    put("begin", begin)
                    put("num", num)
                })
            })
            put("comm", buildComm())
        }
        return request(envelope)
    }

    /**
     * 播放直链：vkey CgiGetVkey，按音质文件名 ladder 降级尝试，返回第一个可用直链。
     * 匿名 uin="0" 时免费曲目通常能拿到 128k（M500）。
     */
    fun getSongPlaybackUrl(songMid: String, quality: String = "standard"): String? {
        qualityFilenameLadder(songMid, quality).forEach { filename ->
            val result = runCatching {
                parseVkey(request(buildVkeyEnvelope(songMid, filename)))
            }.getOrElse { error ->
                if (debugLogging) NPLogger.w("QQMusicClient", "vkey failed for $filename", error)
                null
            }
            when (result) {
                is QQMusicPlaybackResponseParser.PlaybackResult.Success -> return result.url
                QQMusicPlaybackResponseParser.PlaybackResult.RequiresLogin -> {
                    // 需要登录的更高音质，继续尝试更低的免费档位
                }
                is QQMusicPlaybackResponseParser.PlaybackResult.Failure -> Unit
                null -> Unit
            }
        }
        return null
    }

    // ---------------------------------------------------------------------------------------------
    // vkey 构造 / 解析
    // ---------------------------------------------------------------------------------------------

    internal fun buildVkeyEnvelope(songMid: String, filename: String): JSONObject {
        val uin = currentUin()
        val loginFlag = if (uin == "0") 0 else 1
        return JSONObject().apply {
            put("req", JSONObject().apply {
                put("module", "CDN.SrfCdnDispatchServer")
                put("method", "GetCdnDispatch")
                put("param", JSONObject().apply {
                    put("guid", guid)
                    put("calltype", 0)
                    put("userip", "")
                })
            })
            put("req_0", JSONObject().apply {
                put("module", "vkey.GetVkeyServer")
                put("method", "CgiGetVkey")
                put("param", JSONObject().apply {
                    put("guid", guid)
                    put("songmid", JSONArray().put(songMid))
                    put("songtype", JSONArray().put(0))
                    put("uin", uin)
                    put("loginflag", loginFlag)
                    put("platform", "20")
                    put("filename", JSONArray().put(filename))
                })
            })
            put("comm", buildComm())
        }
    }

    /** 音质文件名 ladder，从高到低；quality 控制起始档。 */
    internal fun qualityFilenameLadder(songMid: String, quality: String): List<String> {
        val all = listOf(
            "F000$songMid.flac",
            "M800$songMid.mp3",
            "M500$songMid.mp3",
            "C400$songMid.m4a"
        )
        val start = when (quality.lowercase()) {
            "flac", "lossless", "dolby", "master" -> 0
            "320", "high", "hires" -> 1
            "128", "low", "standard", "normal" -> 2
            else -> 0
        }
        return all.drop(start)
    }

    private fun parseVkey(raw: String): QQMusicPlaybackResponseParser.PlaybackResult =
        QQMusicPlaybackResponseParser.parseVkeyResponse(raw)

    // ---------------------------------------------------------------------------------------------
    // 请求引擎
    // ---------------------------------------------------------------------------------------------

    private fun buildComm(): JSONObject = JSONObject().apply {
        put("uin", currentUin())
        put("format", "json")
        put("ct", 24)
        put("cv", 0)
    }

    private fun request(envelope: JSONObject): String {
        val uin = currentUin()
        val url = HttpUrl.Builder()
            .scheme("https")
            .host(HOST)
            .addPathSegments("cgi-bin/musicu.fcg")
            .addQueryParameter("g_tk", QQMusicCrypto.gTk(skey()).toString())
            .addQueryParameter("loginUin", uin)
            .addQueryParameter("hostUin", "0")
            .addQueryParameter("format", "json")
            .addQueryParameter("inCharset", "utf8")
            .addQueryParameter("outCharset", "utf-8")
            .addQueryParameter("notice", "0")
            .addQueryParameter("platform", "yqq.json")
            .addQueryParameter("needNewCode", "0")
            .addQueryParameter("data", envelope.toString())
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Referer", "https://y.qq.com/")
            .header("User-Agent", USER_AGENT)
            .header("Cookie", buildCookieHeader())
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                if (debugLogging) {
                    NPLogger.w("QQMusicClient", "musicu.fcg http ${response.code} body=${body.take(200)}")
                }
            }
            return body
        }
    }

    private fun buildCookieHeader(): String = synchronized(sessionLock) {
        if (persistedCookies.isEmpty()) return@synchronized ""
        persistedCookies.entries.joinToString("; ") { (k, v) -> "$k=$v" }
    }

    private fun generateGuid(): String {
        val random = SecureRandom()
        // QQ guid 为 10 位数字
        val sb = StringBuilder()
        repeat(10) { sb.append(random.nextInt(10)) }
        return sb.toString()
    }

    private companion object {
        const val HOST = "u.y.qq.com"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0 Mobile Safari/537.36"
    }
}
