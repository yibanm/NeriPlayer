package moe.ouom.neriplayer.platform.qqmusic.api.playback.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QQMusicPlaybackResponseParserTest {

    @Test
    fun `success joins sip host with purl and detects mp3 type`() {
        val raw = """
            {
              "req": { "data": { "sip": ["https://dl.stream.qqmusic.qq.com/"] } },
              "req_0": {
                "code": 0,
                "data": {
                  "midurlinfo": [
                    { "purl": "M500001abc.mp3?vkey=ABC", "songmid": "001abc", "status": 0 }
                  ]
                }
              }
            }
        """.trimIndent()

        val result = QQMusicPlaybackResponseParser.parseVkeyResponse(raw)

        assertTrue(result is QQMusicPlaybackResponseParser.PlaybackResult.Success)
        result as QQMusicPlaybackResponseParser.PlaybackResult.Success
        assertEquals("https://dl.stream.qqmusic.qq.com/M500001abc.mp3?vkey=ABC", result.url)
        assertEquals("mp3", result.type)
        assertEquals("001abc", result.songMid)
    }

    @Test
    fun `missing sip falls back to default cdn host`() {
        val raw = """
            {
              "req_0": {
                "code": 0,
                "data": {
                  "midurlinfo": [ { "purl": "F000xyz.flac", "status": 0 } ]
                }
              }
            }
        """.trimIndent()

        val result = QQMusicPlaybackResponseParser.parseVkeyResponse(raw)

        assertTrue(result is QQMusicPlaybackResponseParser.PlaybackResult.Success)
        result as QQMusicPlaybackResponseParser.PlaybackResult.Success
        assertEquals("https://dl.stream.qqmusic.qq.com/F000xyz.flac", result.url)
        assertEquals("flac", result.type)
    }

    @Test
    fun `negative auth code maps to RequiresLogin`() {
        val raw = """
            { "req_0": { "code": -100, "data": { "midurlinfo": [ { "purl": "", "status": -1 } ] } } }
        """.trimIndent()

        val result = QQMusicPlaybackResponseParser.parseVkeyResponse(raw)

        assertTrue(result is QQMusicPlaybackResponseParser.PlaybackResult.RequiresLogin)
    }

    @Test
    fun `empty purl with nonzero status maps to no permission`() {
        val raw = """
            {
              "req_0": {
                "code": 0,
                "data": { "midurlinfo": [ { "purl": "", "status": 2000 } ] }
              }
            }
        """.trimIndent()

        val result = QQMusicPlaybackResponseParser.parseVkeyResponse(raw)

        assertTrue(result is QQMusicPlaybackResponseParser.PlaybackResult.Failure)
        result as QQMusicPlaybackResponseParser.PlaybackResult.Failure
        assertEquals(QQMusicPlaybackResponseParser.FailureReason.NO_PERMISSION, result.reason)
    }

    @Test
    fun `empty purl with zero status maps to no play url`() {
        val raw = """
            {
              "req_0": {
                "code": 0,
                "data": { "midurlinfo": [ { "purl": "null", "status": 0 } ] }
              }
            }
        """.trimIndent()

        val result = QQMusicPlaybackResponseParser.parseVkeyResponse(raw)

        assertTrue(result is QQMusicPlaybackResponseParser.PlaybackResult.Failure)
        result as QQMusicPlaybackResponseParser.PlaybackResult.Failure
        assertEquals(QQMusicPlaybackResponseParser.FailureReason.NO_PLAY_URL, result.reason)
    }

    @Test
    fun `malformed json maps to unknown failure`() {
        val result = QQMusicPlaybackResponseParser.parseVkeyResponse("not json")
        assertTrue(result is QQMusicPlaybackResponseParser.PlaybackResult.Failure)
        result as QQMusicPlaybackResponseParser.PlaybackResult.Failure
        assertEquals(QQMusicPlaybackResponseParser.FailureReason.UNKNOWN, result.reason)
    }
}
