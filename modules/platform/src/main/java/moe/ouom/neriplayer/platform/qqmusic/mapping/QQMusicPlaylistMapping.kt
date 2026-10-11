package moe.ouom.neriplayer.platform.qqmusic.mapping

import org.json.JSONArray
import org.json.JSONObject

/** 一个远端歌单（收藏夹/自建列表）。 */
data class QQMusicRemotePlaylist(
    val dissId: String,
    val title: String,
    val songCount: Int,
    val coverUrl: String
)

/** 歌单详情里的单条曲目（QQ ID 全部是字符串 mid）。 */
data class QQMusicPlaylistTrack(
    val songMid: String,
    val songName: String,
    val singer: String,
    val albumName: String
)

/**
 * 解析 GetDissList（用户歌单列表）响应。QQ IDs 是字符串 mid，绝不当 Long 处理。
 */
fun parseQQMusicRemotePlaylists(raw: String): List<QQMusicRemotePlaylist> {
    return runCatching {
        val root = JSONObject(raw)
        val req1 = root.optJSONObject("req_1") ?: return@runCatching emptyList()
        val data = req1.optJSONObject("data") ?: return@runCatching emptyList()
        val dissList = data.optJSONArray("list") ?: return@runCatching emptyList()
        buildList {
            for (i in 0 until dissList.length()) {
                val item = dissList.optJSONObject(i) ?: continue
                val dissId = item.optString("dissid", "").ifBlank {
                    item.optString("dirid", "")
                }
                if (dissId.isBlank()) continue
                add(
                    QQMusicRemotePlaylist(
                        dissId = dissId,
                        title = item.optString("title", ""),
                        songCount = item.optInt("song_num", 0),
                        coverUrl = item.optString("imgurl", "")
                    )
                )
            }
        }
    }.getOrDefault(emptyList())
}

/**
 * 解析 GetDissInfo（歌单详情）响应中的曲目列表。
 */
fun parseQQMusicPlaylistTracks(raw: String): List<QQMusicPlaylistTrack> {
    return runCatching {
        val root = JSONObject(raw)
        val req0 = root.optJSONObject("req_0") ?: return@runCatching emptyList()
        val data = req0.optJSONObject("data") ?: return@runCatching emptyList()
        val tracks = data.optJSONArray("list") ?: return@runCatching emptyList()
        tracks.toStringTracks()
    }.getOrDefault(emptyList())
}

private fun JSONArray.toStringTracks(): List<QQMusicPlaylistTrack> {
    return buildList {
        for (i in 0 until length()) {
            val item = optJSONObject(i) ?: continue
            val mid = item.optString("songmid", "")
            if (mid.isBlank()) continue
            add(
                QQMusicPlaylistTrack(
                    songMid = mid,
                    songName = item.optString("songname", item.optString("title", "")),
                    singer = item.optJSONArray("singer").joinSingerNames(),
                    albumName = item.optJSONObject("album")?.optString("title", "").orEmpty()
                )
            )
        }
    }
}

private fun JSONArray?.joinSingerNames(): String {
    if (this == null || length() == 0) return ""
    return buildList {
        for (i in 0 until length()) {
            val name = optJSONObject(i)?.optString("name", "").orEmpty()
            if (name.isNotBlank()) add(name)
        }
    }.joinToString(" / ")
}
