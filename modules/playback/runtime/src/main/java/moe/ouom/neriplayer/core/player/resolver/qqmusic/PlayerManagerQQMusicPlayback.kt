package moe.ouom.neriplayer.core.player.resolver.qqmusic

import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.runtime.refresh.RefreshResolverSideEffects
import moe.ouom.neriplayer.core.player.url.normalizeNeteaseMimeType
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.PlayerEvent
import moe.ouom.neriplayer.data.model.playback.SongUrlResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * QQ Music 播放直链解析。QQ 曲目用字符串 songmid 标识，存在 [SongItem.audioId] 中；
 * 直链由 [PlayerManager.qqMusicClient] 内部做 vkey 音质 ladder 降级后返回完整 URL。
 */
internal suspend fun PlayerManager.getQQMusicSongUrl(
    song: SongItem,
    suppressError: Boolean = false,
    sideEffects: RefreshResolverSideEffects = RefreshResolverSideEffects()
): SongUrlResult = withContext(Dispatchers.IO) {
    try {
        val mid = song.audioId?.takeIf { it.isNotBlank() }
            ?: song.matchedSongId?.takeIf { it.isNotBlank() }
        if (mid.isNullOrBlank()) {
            NPLogger.w("NERI-PlayerManager", "QQ Music song missing songmid: ${song.name}")
            return@withContext SongUrlResult.Failure
        }

        val url = qqMusicClient.getSongPlaybackUrl(mid, quality = "standard")
        if (url.isNullOrBlank()) {
            NPLogger.w("NERI-PlayerManager", "QQ Music vkey returned no url: mid=$mid")
            if (!suppressError) {
                sideEffects.emitError {
                    postPlayerEvent(
                        PlayerEvent.ShowError(getLocalizedString(CoreCommonR.string.error_no_play_url))
                    )
                }
            }
            return@withContext SongUrlResult.Failure
        }

        val httpsUrl = if (url.startsWith("http://")) {
            url.replaceFirst("http://", "https://")
        } else {
            url
        }
        NPLogger.d("NERI-PlayerManager", "QQ Music resolved url: mid=$mid")
        SongUrlResult.Success(
            url = httpsUrl,
            mimeType = normalizeNeteaseMimeType(mimeTypeFromUrl(httpsUrl)),
            durationMs = song.durationMs.takeIf { it > 0L }
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        NPLogger.e("NERI-PlayerManager", "Failed to resolve QQ Music url", e)
        if (!suppressError) {
            sideEffects.emitError {
                postPlayerEvent(
                    PlayerEvent.ShowError(
                        getLocalizedString(
                            CoreCommonR.string.player_playback_url_error_detail,
                            e.message.orEmpty()
                        )
                    )
                )
            }
        }
        SongUrlResult.Failure
    }
}

private fun mimeTypeFromUrl(url: String): String? = when {
    url.contains(".flac", ignoreCase = true) -> "flac"
    url.contains(".mp3", ignoreCase = true) -> "mp3"
    url.contains(".m4a", ignoreCase = true) || url.contains(".aac", ignoreCase = true) -> "m4a"
    else -> null
}
