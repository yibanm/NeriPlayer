package moe.ouom.neriplayer.platform.qqmusic.mapping

import org.json.JSONArray
import org.json.JSONObject

/** 歌手摘要。 */
data class QQMusicArtistSummary(
    val singermid: String,
    val name: String,
    val avatarUrl: String
)

/** 歌手热门曲目列表项。 */
data class QQMusicArtistSong(
    val songMid: String,
    val songName: String,
    val singer: String
)

/**
 * 解析 GetSingerDetail（歌手详情）响应。
 */
fun parseQQMusicArtistDetail(raw: String): QQMusicArtistSummary? {
    return runCatching {
        val root = JSONObject(raw)
        val req0 = root.optJSONObject("req_0") ?: return@runCatching null
        val data = req0.optJSONObject("data") ?: return@runCatching null
        QQMusicArtistSummary(
            singermid = data.optString("singermid", ""),
            name = data.optString("name", ""),
            avatarUrl = data.optString("head_pic_url", data.optString("avatar", ""))
        ).takeIf { it.singermid.isNotBlank() }
    }.getOrNull()
}

/**
 * 解析 GetSingerSong（歌手曲目）响应。QQ IDs 是字符串 mid。
 */
fun parseQQMusicArtistSongs(raw: String): List<QQMusicArtistSong> {
    return runCatching {
        val root = JSONObject(raw)
        val req1 = root.optJSONObject("req_1") ?: return@runCatching emptyList()
        val data = req1.optJSONObject("data") ?: return@runCatching emptyList()
        val songs = data.optJSONArray("list") ?: return@runCatching emptyList()
        buildList {
            for (i in 0 until songs.length()) {
                val item = songs.optJSONObject(i) ?: continue
                val mid = item.optString("songmid", "")
                if (mid.isBlank()) continue
                add(
                    QQMusicArtistSong(
                        songMid = mid,
                        songName = item.optString("songname", item.optString("title", "")),
                        singer = item.optJSONArray("singer").joinNames()
                    )
                )
            }
        }
    }.getOrDefault(emptyList())
}

/** 从歌曲/搜索结果 JSONArray 中抽取歌手摘要列表。 */
fun parseQQMusicArtistsFromSongs(array: JSONArray?): List<QQMusicArtistSummary> {
    array ?: return emptyList()
    return buildList {
        for (i in 0 until array.length()) {
            val song = array.optJSONObject(i) ?: continue
            val singers = song.optJSONArray("singer") ?: continue
            for (j in 0 until singers.length()) {
                val entry = singers.optJSONObject(j) ?: continue
                val mid = entry.optString("mid", entry.optString("singermid", ""))
                val name = entry.optString("name", "")
                if (mid.isNotBlank() && name.isNotBlank()) {
                    add(QQMusicArtistSummary(mid, name, entry.optString("avatar", "")))
                }
            }
        }
    }.distinctBy { it.singermid }
}

private fun JSONArray?.joinNames(): String {
    if (this == null || length() == 0) return ""
    return buildList {
        for (i in 0 until length()) {
            val name = optJSONObject(i)?.optString("name", "").orEmpty()
            if (name.isNotBlank()) add(name)
        }
    }.joinToString(" / ")
}
