# QQ Music (QQ_MUSIC) Integration Map for NeriPlayer

> READ-ONLY reconnaissance document. Purpose: give the implementing engineer an exact
> blueprint to add a full QQ Music platform by mirroring the existing NetEase Cloud Music
> (`CLOUD_MUSIC`) implementation. Every path below is absolute under repo root
> `/home/user/Doubao/chats/38446073235624962/NeriPlayer`. No source files were modified.

---

## 0. TL;DR — current state

- `MusicPlatform` already has **both** values:
  `modules/model/src/main/java/moe/ouom/neriplayer/data/model/music/MusicPlatform.kt`
  ```kotlin
  enum class MusicPlatform {
      CLOUD_MUSIC, QQ_MUSIC
  }
  ```
- Existing QQ code is **only**: anonymous search + native lyric + lyric matching + lyric codec.
  There is **NO** `qqmusic/` package under `modules/platform/src/main` — no client, no auth,
  no playback resolver, no playlist, no artist, no download source.
- The QQ account card already renders in Settings/Onboarding but is hard-disabled
  ("coming soon", `supported = false`).
- **Critical architectural fact:** playback routing is NOT done off the `MusicPlatform` enum.
  It is done off `SongItem` heuristics (`channelId`, `album` prefix, `audioId`). Today a
  `QQ_MUSIC` song silently falls into the NetEase (`else`) playback branch. Adding QQ playback
  requires a QQ source predicate + resolver, not just a `when` on the enum.

---

## 1. NETEASE END-TO-END TEMPLATE (the vertical slice to mirror)

### 1.1 API client + session/cookies

File: `modules/platform/src/main/java/moe/ouom/neriplayer/platform/netease/api/client/NeteaseClient.kt`

```kotlin
class NeteaseClient(
    private val commentCheckToken: suspend () -> String
) {
    fun hasLogin(): Boolean
    fun setPersistedCookies(cookies: Map<String, String>)
    fun getCookies(): Map<String, String>
    fun getNeteaseRequestCookies(): Map<String, String>
    fun logout()
    fun evictConnections()
    fun ensureWeapiSession() / ensurePersonalizedSession()

    // low-level request engine
    @Throws(IOException::class)
    fun request(url: String, params: Map<String, Any>,
                mode: CryptoMode = CryptoMode.WEAPI, method: String = "POST",
                usePersistedCookies: Boolean = true,
                retryHttp1OnStreamReset: Boolean = false): String

    // named gateway helpers (build URL + call request(...))
    fun callWeApi(path: String, params: Map<String, Any>, usePersistedCookies: Boolean = true): String
    fun callEApi(path: String, params: Map<String, Any>, usePersistedCookies: Boolean = true,
                 retryHttp1OnStreamReset: Boolean = false): String
    fun callLinuxApi(path: String, params: Map<String, Any>, usePersistedCookies: Boolean = true): String
    ...
}
```

Key facts to mirror:
- **Base hosts:** `music.163.com` (weapi/api), `interface.music.163.com` (eapi),
  `interface3.music.163.com`. Constants at top: `NETEASE_MAIN_HOST = "music.163.com"`.
- **Cookie/session model** is self-contained inside the client:
  - `internal class NeteaseRequestSession(initialPersistedCookies)` — owns an in-memory
    `cookieStore: MutableMap<String, MutableList<Cookie>>`, a custom `CookieJar`, and its own
    `OkHttpClient` (`.cookieJar(...)`, `.proxySelector(DynamicProxySelector)`).
    Login identity = presence of persisted cookie `MUSIC_U`.
  - `internal class NeteaseRequestSessionStore` — swaps sessions atomically when persisted
    cookies change (`setPersistedCookies`, `logout`).
  - Helper `mergeNeteaseRequestCookies(persisted, runtime, requestContext)` always injects
    `os=pc`, `appver=8.10.35`.
- **Request building** (`buildRequest`): selects encryption by `CryptoMode`, sets
  `Referer: https://music.163.com`, a desktop `User-Agent`, and for WEAPI appends
  `?csrf_token=<__csrf>`. POST uses `FormBody`; GET uses query params.
- **Response decoding** handles `br` (Brotli) and `gzip`, capped at `MAX_RESPONSE_BYTES = 4 MiB`.
- **Business endpoints already on the client** (mirror these as QQ equivalents):
  - Search: `searchSongs(...)`, `searchSongsCancellable(...)` → `/weapi/cloudsearch/get/web`
  - Playback: `getSongDownloadUrl(songId, level="lossless")` → eapi `/song/enhance/player/url/v1`;
    `getSongUrl(songId, bitrate)` → weapi `/song/enhance/player/url`
  - Song detail: `getSongDetail(ids)` → `/weapi/v3/song/detail`
  - Playlists: `getUserPlaylists(uid)`, `getPlaylistDetail(id)` → `/api/v6/playlist/detail`,
    `addSongsToPlaylist(playlistId, songIds)` → `/playlist/manipulate/tracks`,
    `getLikedPlaylistId(userId)`, `getUserCreatedPlaylists`, `getUserSubscribedPlaylists`
  - Artist: `getArtistDetail(id)` → `/api/artist/head/info/get`,
    `getArtistSongs(id,...)` → `/api/v1/artist/songs`,
    `getFollowedArtists(...)` → `/weapi/artist/sublist`, `getArtistAlbums(id)`
  - Login: `loginByPhone`, `sendCaptcha`, `verifyCaptcha`, `loginByCaptcha`, `getCurrentUserAccount`.

### 1.2 Crypto (weapi/eapi)

File: `modules/platform/src/main/java/moe/ouom/neriplayer/platform/netease/api/crypto/NeteaseCrypto.kt`

```kotlin
object NeteaseCrypto {
    fun randomKey(): String
    fun md5Hex(data: String): String
    fun weApiEncrypt(payload: Map<String, Any>): Map<String, String>   // AES-CBC(base62 key)+RSA
    fun eApiEncrypt(url: String, payload: Map<String, Any>): Map<String, String>
    fun linuxApiEncrypt(payload: Map<String, Any>): Map<String, Any>
    fun linuxApiDecrypt(cipher: String): String
    fun anonymous(deviceId: String): String
    fun generateWnmcId(): String
}
```
Mode enum: `modules/model/src/main/java/moe/ouom/neriplayer/data/model/netease/crypto/CryptoMode.kt`
```kotlin
enum class CryptoMode { WEAPI, EAPI, LINUX, API }
```
QQ Music needs its own crypto module (its `musicu.fcg` protocol + `g_tk` hash). Do **not** reuse
`NeteaseCrypto`. See §3 — existing QQ calls are plain (no crypto yet); only `g_tk=5381` static.

### 1.3 Login — QR flow + cookie persistence + auth bundle

QR client: `modules/platform/src/main/java/moe/ouom/neriplayer/platform/netease/api/auth/NeteaseQrLoginClient.kt`
```kotlin
class NeteaseQrLoginClient(context: Context) {
    fun reset()
    suspend fun createSession(): NeteaseQrLoginSession          // POST /weapi/login/qrcode/unikey -> unikey
    fun checkLogin(session: NeteaseQrLoginSession): NeteaseQrLoginCheckResult  // POST /weapi/login/qrcode/client/login
    fun currentCookies(): Map<String, String>
}
```
- Poll codes handled by UI: `801` waiting, `802` scanned, `803` confirmed, `800` expired.
- On `803`, `verifyConfirmedLogin(refreshToken)` merges header `x-refresh-token` into cookie
  `MUSIC_U` and re-checks `/weapi/w/nuser/account/get`.
- QR scan URL built by `buildScanLoginUrl(key, chainId)` →
  `https://music.163.com/st/platform/scanlogin?codekey=...&chainId=...`.
- Session models live in the **model** module:
  `modules/model/src/main/java/moe/ouom/neriplayer/data/model/netease/auth/`
  (`NeteaseQrLoginSession.kt`, `NeteaseQrLoginCheckResult.kt`, `NeteaseQrHttpResult.kt`,
  `NeteaseYdDeviceSnapshot.kt`).

Cookie persistence: `modules/platform/src/main/java/moe/ouom/neriplayer/platform/netease/auth/NeteaseCookieRepository.kt`
```kotlin
class NeteaseCookieRepository(context: Context) {
    val cookieFlow: StateFlow<Map<String, String>>
    val authHealthFlow: StateFlow<SavedCookieAuthHealth>
    fun getCookiesOnce(): Map<String, String>
    fun saveCookies(cookies: Map<String, String>, savedAt: Long = ...): Boolean
    fun saveCookiesIfCurrent(expected, cookies, savedAt): Boolean
    fun validateCookies(cookies): NeteaseCookieValidationResult
    fun clear()
    fun refreshHealth()
}
```
- Storage: `EncryptedSharedPreferences` name `"netease_auth_secure_prefs"`, key
  `"netease_auth_bundle"`, opened via `SecurePreferencesOpener` with keystore-fallback to a
  `VolatileSharedPreferences`. There is also a legacy `DataStore("auth_store")` migration path.
- Validation helper: `validateAndSanitizeNeteaseCookies(cookies, includeFallbackCookies)` rejects
  control chars / `;` / illegal cookie-name chars.

Auth bundle codec: `.../netease/auth/NeteaseAuthBundleCodec.kt`
```kotlin
fun NeteaseAuthBundle.toJson(): String
fun NeteaseAuthBundle.Companion.fromJson(json: String): NeteaseAuthBundle
```
Auth bundle model: `modules/model/.../netease/auth/NeteaseAuthBundle.kt`
```kotlin
val NETEASE_LOGIN_COOKIE_KEYS = listOf("MUSIC_U")
data class NeteaseAuthBundle(val cookies: Map<String,String> = emptyMap(), val savedAt: Long = 0L) {
    fun hasLoginCookies(): Boolean
    fun normalized(savedAt: Long = this.savedAt): NeteaseAuthBundle
}
```

QR UI host: `app/src/main/java/moe/ouom/neriplayer/activity/auth/NeteaseQrLoginActivity.kt`
```kotlin
class NeteaseQrLoginActivity : ComponentActivity() {
    companion object { const val RESULT_COOKIE = "result_cookie_map_json" }
    private val qrClient by lazy { NeteaseQrLoginClient(this) }
    // startQrLogin(): qrClient.createSession() -> render QR bitmap -> repeatOnLifecycle { pollQrLogin(session) }
    // pollQrLogin(): qrClient.checkLogin(session); on 803 -> finishWithCookies(check.cookies)
    // finishWithCookies(): validates MUSIC_U present, setResult(RESULT_OK, Intent putExtra RESULT_COOKIE = JSON)
}
```
Result is consumed in `app/.../ui/screen/tab/settings/auth/SettingsNeteaseAuthDialogs.kt`
(`rememberLauncherForActivityResult` → `vm.importCookiesFromMap(parseCookieMap(json))`).

View Model: `app/src/main/java/moe/ouom/neriplayer/ui/viewmodel/debug/NeteaseAuthViewModel.kt`
```kotlin
class NeteaseAuthViewModel(app: Application) : AndroidViewModel(app) {
    private val cookieRepo by lazy { AppContainer.neteaseCookieRepo }
    private val api by lazy { AppContainer.neteaseClient }
    val uiState: StateFlow<NeteaseAuthUiState>
    val events: MutableSharedFlow<NeteaseAuthEvent>
    fun refreshAuthHealth(); fun clearCookies()
    fun sendCaptcha(ctcode: String); fun loginByCaptcha(countryCode: String)
    fun importCookiesFromMap(map: Map<String,String>); fun importCookiesFromRaw(raw: String)
}
sealed interface NeteaseAuthEvent { ShowSnack; AskConfirmSend; LoginSuccess }
```

### 1.4 Search (song search → SongSearchInfo mapping)

Interface: `modules/platform/src/main/java/moe/ouom/neriplayer/platform/search/api/SearchApi.kt`
```kotlin
interface SearchApi {
    suspend fun search(keyword: String, page: Int): List<SongSearchInfo>
    suspend fun getSongInfo(id: String): SongDetails
}
interface NativeLyricSearchApi : SearchApi { suspend fun getNativeSongInfo(id: String): SongDetails }
```
NetEase impl: `.../search/api/client/CloudMusicSearchApi.kt`
```kotlin
class CloudMusicSearchApi(
    private val neteaseClient: NeteaseClient,
    private val client: OkHttpClient,
    private val debugLogging: Boolean = false
) : SearchApi
```
- `search()` calls `neteaseClient.searchSongsCancellable(keyword, limit=20, offset=(page-1)*20,
  usePersistedCookies=false)`, decodes `CloudMusicSearchResponse`, maps to `SongSearchInfo(
  id = song.id.toString(), source = MusicPlatform.CLOUD_MUSIC, ...)`.
- `getSongInfo(id)` hits `/api/song/detail`, then fetches lyrics.
- Model responses: `modules/model/.../netease/search/CloudMusicModels.kt`
  (`CloudMusicSearchResponse`, `CloudMusicSongDetailResponse`, `CloudMusicLyricResponse`).

### 1.5 Playback / stream resolution

Entry point: `modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/url/PlayerManagerUrlExtensions.kt`
```kotlin
internal suspend fun PlayerManager.resolveSongUrl(
    song: SongItem,
    forceRefresh: Boolean = false,
    youtubeRecoveryStrategy: YouTubePlaybackRecoveryStrategy? = null,
    sideEffects: RefreshResolverSideEffects = RefreshResolverSideEffects(),
    allowGenericPrefetchCache: Boolean = true,
    playbackRequestTokenOverride: Long? = null,
    shouldApplyCacheMutation: () -> Boolean = { true }
): SongUrlResult
```
**The dispatch switch (lines ~363–384) — THIS IS WHERE QQ MUST BE ADDED:**
```kotlin
when {
    isYouTubeTrack -> getYouTubeMusicAudioUrl(...)
    isBiliTrack(song) -> getBiliAudioUrl(...)
    else -> getNeteaseSongUrl(...)   // <-- QQ songs currently land here and fail
}
```
NetEase resolver: `private suspend fun PlayerManager.getNeteaseSongUrl(song, suppressError,
sideEffects, allowLocalFallback, allowPreviewFallback, allowAutoBiliFallback): SongUrlResult`
(line 1622). It loops quality candidates (`buildNeteaseQualityCandidates`), calls
`neteaseClient.getSongDownloadUrl(song.id, level)`, and parses via:
```kotlin
// modules/platform/.../netease/api/playback/parser/NeteasePlaybackResponseParser.kt
object NeteasePlaybackResponseParser {
    sealed class PlaybackResult {
        data class Success(url, type, level, bitrateKbps, notice, contentLength, songId, durationMs, contentMd5)
        object RequiresLogin
        data class Failure(val reason: FailureReason = FailureReason.UNKNOWN)
    }
    enum class Notice { PREVIEW_CLIP }
    enum class FailureReason { NO_PERMISSION, NO_PLAY_URL, UNKNOWN }
    fun parsePlayback(rawResponse: String, originalDurationMs: Long): PlaybackResult
    fun parseDownloadInfo(rawResponse: String): DownloadInfo?
}
```
**How the player picks a platform for a Song — NOT the enum.** Predicates in
`modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/media/PlaybackMediaItemFactory.kt`:
```kotlin
internal object PlaybackMediaItemFactory {
    const val BILI_SOURCE_TAG = SongSourceTags.BILIBILI
    fun cacheKey(song, context, youtubeQualityOverride, youtubePreferM4a,
                 neteaseFallbackEnabled, youtubeQuality, biliQuality, neteaseQuality): String
    fun isYouTubeSource(song: SongItem): Boolean =
        song.channelId == ListenTogetherChannels.YOUTUBE_MUSIC || isYouTubeMusicSong(song)
    fun isBiliSource(song: SongItem): Boolean =
        song.channelId == ListenTogetherChannels.BILIBILI || song.album.startsWith(BILI_SOURCE_TAG)
    fun neteaseCacheKey(songId: Long, preferredQuality: String, useFallbackNamespace: Boolean): String
    fun neteasePreviewCacheKey(songId: Long, preferredQuality: String): String
}
```
And on `PlayerManager` (`.../core/player/PlayerManager.kt`):
```kotlin
internal fun isYouTubeMusicTrack(song: SongItem): Boolean = PlaybackMediaItemFactory.isYouTubeSource(song)
fun isBiliTrack(song: SongItem): Boolean = PlaybackMediaItemFactory.isBiliSource(song)
```
> **Note:** `SongItem` (`modules/model/.../data/model/SongItem.kt`) has **no `platform` field**.
> It carries `channelId`, `audioId`, `subAudioId`, `album`. Platform is inferred. QQ songs are
> currently indistinguishable → they fall through to NetEase. A QQ integration must either add an
> explicit QQ predicate (e.g. `isQQMusicSource(song)`, mirroring `isBiliSource`) and a cache-key
> branch, or (cleaner) thread a `MusicPlatform` through `SongItem`. Decide deliberately.

### 1.6 Download

Download source resolver: `modules/download/runtime/src/main/java/moe/ouom/neriplayer/core/player/download/source/AudioDownloadSourceResolver.kt`
```kotlin
internal object AudioDownloadSourceResolver {
    internal fun isBiliSource(song: SongItem): Boolean = song.identity().album == "bilibili"
    internal suspend fun resolveNetease(songId: Long, preferredQuality: String): ResolvedDownloadSource?
    internal suspend fun resolveYouTubeMusic(song, preferredQuality, forceRefresh, avoidDirect): ResolvedDownloadSource?
    internal suspend fun resolveBili(song: SongItem, preferredQuality: String): ResolvedDownloadSource?
    internal fun parseNeteaseDownloadLookup(rawResponse: String, expectedSongId: Long?): NeteaseDownloadLookup
}
```
Dispatch: `modules/download/runtime/.../runtime/AudioDownloadManagerAttempt.kt`
```kotlin
internal suspend fun AudioDownloadManager.resolveDownloadSourceForAttempt(
    song, downloadAudioQuality: DownloadAudioQualitySelection,
    isYouTubeMusic: Boolean, isBili: Boolean, state: DownloadExecutionAttemptState
): ResolvedDownloadSource? {
    return sourceResolveSemaphore.withPermit {
        when {
            isYouTubeMusic -> resolveYouTubeMusic(...)
            isBili -> resolveBili(...)
            else -> resolveNetease(song.id, downloadAudioQuality.neteaseQuality)  // QQ falls here
        }
    }
}
```
Clients are reached via `DownloadHosts.sources.neteaseClient` / `.biliClient` /
`.youtubeMusicPlaybackRepository`. A QQ resolver needs a `resolveQQMusic(song, ...)` + a new
branch here + a QQ quality knob in `DownloadAudioQualitySelection`.

### 1.7 Playlist (remote list / sync / batch add)

Dir: `modules/platform/src/main/java/moe/ouom/neriplayer/platform/netease/playlist/`
```kotlin
// NeteaseRemotePlaylist.kt
fun parseNeteaseRemotePlaylists(raw: String): List<NeteaseRemotePlaylist>

// NeteasePlaylistSync.kt
class NeteasePlaylistSync(...) {
    fun filterNeteaseLikeSyncCandidates(songs: List<SongItem>): List<SongItem>
    suspend fun fetchNeteaseRemotePlaylists(client: NeteaseClient): List<NeteaseRemotePlaylist>
    suspend fun prepareNeteaseLikeSyncPlan(songs, ...)
    suspend fun prepareNeteasePlaylistSyncPlan(...)
    suspend fun syncSongsToNeteaseLiked(...)
    suspend fun syncSongsToNeteasePlaylist(...)
}

// NeteasePlaylistBatchAdd.kt
fun addNeteasePlaylistSongIdsInBatches(ids: List<Long>): NeteasePlaylistBatchAddResult

// NeteasePlaylistSyncRequests.kt
internal fun fetchNeteasePlaylistTrackSnapshot(client, playlistId, ...)
internal fun addNeteasePlaylistSongIdsBatch(client, playlistId, songIds)
internal fun fetchNeteaseLikedSongDetailSummaryByPages(client, ids)
internal fun resolveLikedNeteasePlaylistId(client: NeteaseClient): Long?
internal fun buildNeteasePlaylistSyncPlan(...)
internal fun reconcileNeteasePlaylistAddResult(raw): ...

// NeteasePlaylistSyncPolicy.kt
internal fun resolveNeteaseSongId(song: SongItem): Long?
internal fun parseNeteaseLikedPlaylistId(raw: String): ParsedNeteasePlaylistId
internal fun parseNeteaseTrackIdsFromPlaylistDetail(raw: String): ParsedNeteasePlaylistTrackIds
internal fun SongItem.toNeteaseFingerprint(): String?

// NeteasePlaylistSyncModels.kt — NeteaseRemotePlaylistSyncPlan, NeteaseLikeSyncPlan/Result
```
Playlist cache (app-scoped): `.../netease/NeteasePlaylistCacheRepository.kt`
```kotlin
class NeteasePlaylistCacheRepository private constructor(...) {
    fun read(...); fun save(cache); fun saveIfNewer(cache); fun clear(...); fun importLegacyCaches()
}
```
Registered in DI as `AppContainer.neteasePlaylistCacheRepo`.
Request param builders: `.../netease/api/request/NeteasePlaylistTrackParams.kt`
`internal fun buildNeteasePlaylistAddTracksParams(playlistId, songIds): Map<String, Any>`.
Playlist model types live in `modules/model/.../netease/playlist/` and
`.../netease/cache/NeteasePlaylistCacheModels.kt`.

### 1.8 Artist page

```kotlin
// modules/platform/.../netease/mapping/NeteaseArtistJson.kt
fun parseNeteaseArtistSummaries(array: JSONArray?): List<NeteaseArtistSummary>
fun parseNeteaseArtistsFromSongJson(song: JSONObject?): List<NeteaseArtistSummary>
fun parseNeteaseArtistsFromSongDetail(raw: String): List<NeteaseArtistSummary>

// modules/platform/.../netease/mapping/NeteaseFollowedArtists.kt
data class NeteaseFollowedArtist(...)
data class NeteaseFollowedArtistPage(...)
fun parseNeteaseFollowedArtists(raw: String): NeteaseFollowedArtistPage

// modules/platform/.../netease/api/request/NeteaseArtistRequest.kt
internal fun buildNeteaseFollowedArtistsParams(offset: Int, limit: Int): Map<String, String>
```
Client calls: `NeteaseClient.getArtistDetail(id)`, `getArtistSongs(id, order="hot", offset, limit)`,
`getFollowedArtists(offset, limit)`, `getArtistAlbums(id)`. `NeteaseArtistSummary` is referenced on
`SongItem.neteaseArtists: List<NeteaseArtistSummary>?`.

---

## 2. WIRING / REGISTRATION POINTS (every switch on the platform)

### 2.1 Central DI container

File: `app/src/main/java/moe/ouom/neriplayer/core/di/AppContainer.kt` (`object AppContainer`).

What NetEase registers (mirror for QQ):
```kotlin
// line ~252 — secure auth repo
val neteaseCookieRepo by lazy { NeteaseCookieRepository(application) }

// line ~363 — API client; inject comment-token provider AND seed persisted cookies
val neteaseClient by lazy {
    NeteaseClient { NeteaseYdDeviceTokenProvider(applicationContext).getCommentToken() }
        .also { client ->
            neteaseCookieRepo.withCurrentCookies { cookies -> client.setPersistedCookies(cookies) }
        }
}

// line ~447 — search API
val cloudMusicSearchApi by lazy {
    CloudMusicSearchApi(neteaseClient, sharedOkHttpClient, debugLogging = BuildConfig.DEBUG)
}

// line ~450 — QQ today is ONLY a lyrics repo (not an authenticated client)
val qqMusicSearchApi by lazy {
    QQMusicLyricsRepository(
        api = QQMusicSearchApi(sharedOkHttpClient, debugLogging = BuildConfig.DEBUG),
        amllTtmlClient = amllTtmlClient,
        amllLyricsEnabledProvider = { settingsRepo.amllLyricsEnabledFlow.first() }
    )
}

// line ~457 — THE exhaustive when over MusicPlatform (adds/keeps both branches)
val searchManager by lazy {
    SearchManager { platform ->
        when (platform) {
            MusicPlatform.CLOUD_MUSIC -> cloudMusicSearchApi
            MusicPlatform.QQ_MUSIC -> qqMusicSearchApi
        }
    }
}
```
Other NetEase wiring to mirror:
- `neteasePlaylistCacheRepo` (line ~290).
- `neteaseComments = NeteaseCommentRepository(commentCache) { neteaseClient }` and
  `commentRepositoryFor(platform: CommentPlatform)` (line ~380) — a *separate* enum
  `CommentPlatform { NETEASE, BILIBILI }`; QQ comments are out of scope unless requested.
- `startCookieObserver()` (line ~597): `neteaseCookieRepo.cookieFlow.onEach { ...
  neteaseClient.setPersistedCookies(...) }`. **A QQ client needs an equivalent observer.**
- `initialize(app)` calls `installPlayerDependencies(app)` (line 517) and
  `installDownloadDependencies()` (line 516) — these bind the player/download host interfaces.

### 2.2 Player dependency surface

File: `modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/host/PlayerRepositoryDependencies.kt`
```kotlin
interface PlayerRepositoryDependencies {
    val settingsRepo: SettingsRepository
    val biliCookieRepo: BiliCookieRepository
    val neteaseCookieRepo: NeteaseCookieRepository
    val youtubeAuthRepo: YouTubeAuthRepository
    val biliClient: BiliClient
    val neteaseClient: NeteaseClient
    val youtubeMusicClient: YouTubeMusicClient
    val biliPlaybackRepository: BiliPlaybackRepository
    val youtubeMusicPlaybackRepository: YouTubeMusicPlaybackRepository
    ...
    val cloudMusicSearchApi: CloudMusicSearchApi
    val qqMusicSearchApi: QQMusicLyricsRepository   // <-- note: typed as the lyrics repo today
    ...
    val sharedOkHttpClient: OkHttpClient
}
```
The concrete implementation that satisfies this interface lives in
`app/src/main/java/moe/ouom/neriplayer/core/di/player/` (`installPlayerDependencies`). If a QQ
client/repo must be reachable from the player resolver, add it here and bind it in that installer.

### 2.3 Playback resolver selection (the critical `when`)

`PlayerManagerUrlExtensions.resolveSongUrl` (§1.5) dispatches on `isYouTubeTrack` / `isBiliTrack`
/ else. **No `MusicPlatform` `when` here.** Required edits for QQ playback:
1. Add `fun isQQMusicSource(song: SongItem): Boolean` next to `isBiliTrack`/`isYouTubeMusicTrack`
   (in `PlaybackMediaItemFactory` + exposed on `PlayerManager`).
2. Add a branch in `resolveSongUrl`: `isQQMusicTrack(song) -> getQQMusicSongUrl(...)`.
3. Add `private suspend fun PlayerManager.getQQMusicSongUrl(song, ...): SongUrlResult` (new file
   e.g. `.../resolver/qqmusic/PlayerManagerQQMusicPlayback.kt`), mirroring `getNeteaseSongUrl`.
4. Add a `qqMusicCacheKey(...)` branch in `PlaybackMediaItemFactory.cacheKey`.
5. Mirror in `resolveShareableListenTogetherStreamUrls` (lines ~462–466) if QQ joins listen-together.

### 2.4 Download source selection

`AudioDownloadManagerAttempt.resolveDownloadSourceForAttempt` (§1.6) dispatches on
`isYouTubeMusic` / `isBili` / else-Netease. Add an `isQQMusic` flag + `resolveQQMusic(...)` in
`AudioDownloadSourceResolver`, and a QQ quality field on `DownloadAudioQualitySelection`.

### 2.5 Other `MusicPlatform` / platform `when` sites to keep exhaustive

- `AppContainer.searchManager` (§2.1) — already exhaustive over 2 values.
- `SearchManager.findBestSearchCandidate` (`modules/platform/.../lyrics/search/SearchManager.kt`):
  hard-codes `searchApi(MusicPlatform.QQ_MUSIC)` then `searchApi(MusicPlatform.CLOUD_MUSIC)` for
  lyric matching. Works today; no change needed, but QQ playback metadata will flow through here.
- `modules/local/.../settings/lyrics/LyricSourcePreference.kt` and
  `modules/lyrics/.../offset/LyricDefaultOffset.kt` already reference `QQ_MUSIC` (lyric offset
  defaults `DEFAULT_QQ_MUSIC_LYRIC_OFFSET_MS`). These are lyric-only; no playback implication.
- `modules/model/.../data/model/music/SongSearchInfo.kt` has `source: MusicPlatform`.
- `app/.../ui/viewmodel/ManualSearchRequestCoordinator.kt`,
  `app/.../ui/viewmodel/debug/SearchApiProbeViewModel.kt`,
  `app/.../ui/screen/debug/SearchApiProbeScreen.kt`,
  `app/.../ui/component/playback/SongMetadataSearchContent.kt`,
  `app/.../ui/screen/nowplaying/edit/NowPlayingSongEditSearchSheet.kt` — manual-search/lyric
  "find song on another platform" UIs that enumerate platforms. Check these for any
  `when(platform)` that lists only CLOUD_MUSIC.
- `app/.../ui/screen/tab/library/LibraryScreen*.kt`, `LibraryScreenFavoriteContent.kt` — library
  favorites/content already reference QQ; verify how remote-playlist rows branch.

### 2.6 Account / login UI (where QQ login goes)

- `app/.../ui/screen/tab/settings/auth/SettingsAccountCards.kt`
  ```kotlin
  internal enum class SettingsAccountPlatform(val key: String, val iconRes: Int, val titleRes: Int) {
      Netease("netease", R.drawable.ic_netease_cloud_music, R.string.platform_netease),
      Bilibili("bilibili", R.drawable.ic_bilibili, R.string.platform_bilibili),
      YouTube("youtube", R.drawable.ic_youtube, R.string.common_youtube),
      QqMusic("qq", R.drawable.ic_qq_music, R.string.settings_qq_music)   // ALREADY PRESENT
  }
  ```
  The QQ card renders but is disabled: `val supported = account.platform != SettingsAccountPlatform.QqMusic`
  (shows "coming soon"). To enable: flip `supported`, wire click actions.
- `app/.../ui/screen/tab/settings/auth/SettingsAccountAuthController.kt`
  ```kotlin
  fun openPlatformLogin(platform: SettingsAccountPlatform) = when (platform) {
      SettingsAccountPlatform.Netease -> openNeteaseSheet()
      SettingsAccountPlatform.Bilibili -> openBiliSheet()
      SettingsAccountPlatform.YouTube -> openYouTubeSheet()
      SettingsAccountPlatform.QqMusic -> Unit          // <-- NO-OP today
  }
  fun openPlatformSavedAuthorization(platform: SettingsAccountPlatform) = when (platform) {
      SettingsAccountPlatform.Netease -> openNeteaseSavedCookieDialog()
      SettingsAccountPlatform.Bilibili -> openBiliSavedCookieDialog()
      SettingsAccountPlatform.YouTube -> openYouTubeSavedCookieDialog()
      SettingsAccountPlatform.QqMusic -> Unit          // <-- NO-OP today
  }
  ```
  `SettingsAuthDialogState` has `showNeteaseSheet/...` etc.; add QQ dialog booleans.
  `SettingsAccountAuthDialogs(...)` composes `SettingsNeteaseAccountAuthDialogs(...)`,
  `SettingsBiliAccountAuthDialogs`, `SettingsYouTubeAccountAuthDialogs` — add a QQ one.
- `app/.../ui/onboarding/StartupPlatformAccounts.kt` — builds the card list; QQ entry is
  `SettingsAccountCardUiState(platform = SettingsAccountPlatform.QqMusic)` with no auth flow, and
  the profile loader `when(platform){ ... QqMusic -> null }`. Wire a QQ auth flow here.
- The QR login *Activity* pattern to copy: `NeteaseQrLoginActivity.kt` + result consumed by
  `SettingsNeteaseAuthDialogs.kt`. QQ Music QR/web login would be a new
  `app/src/main/java/moe/ouom/neriplayer/activity/auth/QQMusicQrLoginActivity.kt` returning a
  `RESULT_COOKIE` JSON, saved via a new `QQMusicAuthViewModel.importCookiesFromMap(...)`.

---

## 3. EXISTING QQ CODE (reuse, don't duplicate)

### 3.1 `QQMusicSearchApi` (anonymous search + detail + lyric)
File: `modules/platform/src/main/java/moe/ouom/neriplayer/platform/search/api/client/QQMusicSearchApi.kt`
```kotlin
class QQMusicSearchApi(
    private val client: OkHttpClient,
    private val debugLogging: Boolean = false
) : NativeLyricSearchApi
```
Endpoints & format (NO crypto; only static `g_tk=5381`):
- **Search:** `GET https://c.y.qq.com/soso/fcgi-bin/client_search_cp?format=json&n=20&p={page}&w={keyword}&cr=1&g_tk=5381`
  → decodes `QQMusicSearchResponse`, maps to `SongSearchInfo(id = song.songMid,
  source = MusicPlatform.QQ_MUSIC, coverUrl = "https://y.qq.com/music/photo_new/T002R800x800M000{albumMid}.jpg")`.
- **Song detail:** `GET https://u.y.qq.com/cgi-bin/musicu.fcg?data={...}` where `data` is a JSON
  blob `{"songinfo":{"method":"get_song_detail_yqq","module":"music.pf_song_detail_svr",
  "param":{"song_mid":id}}}` → `QQMusicDetailResponse.data.trackInfo`.
- **Lyric:** same `musicu.fcg` host; `{"req":{"method":"GetPlayLyricInfo",
  "module":"music.musichallSong.PlayLyricInfo","param":{"songMID":mid,"trans":1,"qrc":0,"crypt":0}}}`.
- Methods: `search(keyword,page)`, `getSongInfo(id)`, `getSongMetadata(id): QQMusicSongMetadata`,
  `getNativeSongInfo(id)`, `getNativeLyrics(songMid): Pair<String?,String?>`.
- Reuses codec: `decodeQQMusicLyricPayload(...)`, `stripUntranslatedPlaceholderLines(...)`.

### 3.2 `QQMusicLyricsRepository`
File: `modules/platform/src/main/java/moe/ouom/neriplayer/platform/lyrics/repository/QQMusicLyricsRepository.kt`
```kotlin
class QQMusicLyricsRepository(
    private val api: QQMusicSearchApi,
    private val amllTtmlClient: AmllLyricsRepository,
    private val amllLyricsEnabledProvider: suspend () -> Boolean
) : NativeLyricSearchApi
```
- `getSongInfo(id)` merges QQ native lyric + AMLL word-by-word lyric via `chooseQQMusicLyrics(...)`.
- This is what `AppContainer.qqMusicSearchApi` currently exposes (so QQ *search* and *lyric
  matching* already work end-to-end). **A future authenticated `QQMusicClient` should be wired
  separately** and the anonymous `QQMusicSearchApi` kept for unauthenticated search/lyric.

### 3.3 Supporting QQ files already present
- `modules/platform/src/main/java/moe/ouom/neriplayer/platform/search/api/codec/QQMusicLyricCodec.kt`
  (`decodeQQMusicLyricPayload`, `stripUntranslatedPlaceholderLines`).
- `modules/platform/src/main/java/moe/ouom/neriplayer/platform/lyrics/matching/QQMusicLyricSelection.kt`
  (`chooseQQMusicLyrics(...)`).
- Tests: `modules/platform/src/test/.../search/api/client/QQMusicSearchApiTest.kt`,
  `.../search/api/codec/QQMusicBase64LyricTest.kt`, `.../search/api/codec/QQMusicLyricResponseTest.kt`,
  `.../lyrics/repository/QQMusicLyricsRepositoryTest.kt`,
  `.../lyrics/matching/QQMusicLyricSelectionTest.kt`.

### 3.4 QQ model fields (in `modules/model`)
Dir: `modules/model/src/main/java/moe/ouom/neriplayer/data/model/qqmusic/search/`
```kotlin
// QQMusicModels.kt
@Serializable data class QQMusicSearchResponse(val data: QQMusicSearchData?)
@Serializable data class QQMusicSearchSong(val list: List<QQMusicSongSummary>?)
@Serializable data class QQMusicSongSummary(
    @SerialName("songmid") val songMid: String,
    @SerialName("songname") val songName: String,
    val singer: List<QQMusicArtist>,
    @SerialName("albummid") val albumMid: String?,
    @SerialName("albumname") val albumName: String?,
    val interval: Long                 // seconds
)
@Serializable data class QQMusicArtist(val name: String)
@Serializable data class QQMusicDetailResponse(val data: QQMusicDetailData?)
@Serializable data class QQMusicDetailData(@SerialName("track_info") val trackInfo: QQMusicTrackInfo?)
@Serializable data class QQMusicTrackInfo(
    val mid: String, val name: String,
    val singer: List<QQMusicArtist>,
    val album: QQMusicAlbum,
    val interval: Long = 0L
)
@Serializable data class QQMusicAlbum(val name: String, val mid: String)
@Serializable data class QQMusicLyricResponse(val lyric: String? = null, val trans: String? = null)
@Serializable data class QQMusicLyricContainer(val req: QQMusicLyricEnvelope? = null)
@Serializable data class QQMusicLyricEnvelope(val code: Int = 0, val data: QQMusicLyricResponse? = null)

// QQMusicSongMetadata.kt
data class QQMusicSongMetadata(val details: SongDetails, val durationMs: Long)
```
> Note QQ identifiers are **string `mid`s**, not numeric IDs like NetEase. `SongItem.id: Long`
> cannot hold a QQ mid — QQ resolution keys on `matchedSongId: String?` / `audioId: String?`.
> This is the central data-model mismatch to design around (see §5).

---

## 4. BUILD / TEST GATES

### 4.1 Module ownership & boundary
- Owned modules list: `gradle/owned-modules.txt`. Relevant: `:platform`, `:network`, `:model`,
  `:common`, `:playback:runtime`, `:download:runtime`, `:local`, `:database`, `:lyrics`.
- **Where new QQ code lives:**
  - Platform client/auth/playlist/artist/mapping/playback-parser → `modules/platform/.../platform/qqmusic/`
    (same module as netease). `modules/platform/build.gradle.kts` already has the right deps:
    ```kotlin
    dependencies {
        api(project(":model")); api(project(":database")); api(project(":lyrics"))
        implementation(project(":network")); implementation(project(":common"))
        implementation(libs.okhttp); implementation(libs.kotlinx.serialization.json)
        implementation(libs.androidx.security.crypto); implementation(libs.androidx.datastore.preferences)
        testImplementation(libs.junit); testImplementation(libs.org.json)
        testImplementation(libs.mockito.core); testImplementation(libs.kotlinx.coroutines.test)
    }
    ```
    So **platform may depend on the `:network` module** (`implementation`) and reuses okhttp +
    encrypted-prefs + kotlinx-serialization — exactly what a QQ client/cookie-repo needs.
  - Playback resolver glue → `modules/playback/runtime/.../core/player/resolver/qqmusic/`
    (mirror existing `resolver/netease/`).
  - Download source branch → `modules/download/runtime/.../source/AudioDownloadSourceResolver.kt`.
  - UI/ViewModel/Activity → `app/`.
  - DTOs/models → `modules/model/.../data/model/qqmusic/...`.

### 4.2 Module-boundary rule (MUST read before adding packages)
`modules/platform/build.gradle.kts` registers `verifyDomainDependencies`, which runs
`tools_pub/quality/domain_dependencies.py` over the classes jar using
`config/quality/domain-dependencies.json`, filtered to entries whose `name` starts with `platform-`.
Existing scopes: `platform-netease-client` (pkg `...netease.api.`), `platform-netease-repository`
(`...netease.`), `platform-netease-auth` (`...netease.auth.`), plus bilibili/youtube/lyrics/search.
Each scope has an explicit `allowed_classes` whitelist.
> **Action required:** new `qqmusic` packages will need new entries in
> `config/quality/domain-dependencies.json` (e.g. `platform-qqmusic-client`,
> `platform-qqmusic-auth`, `platform-qqmusic-repository`) mirroring the netease entries, or the
> `verifyDomainDependencies` task will fail. Copy the netease client/auth/repository blocks and
> adjust the `package:` prefix to `...platform.qqmusic...`.

### 4.3 CRAP / coverage
- Convention: `build-logic/convention/src/main/kotlin/build-logic.android.module-quality.gradle.kts`.
  - `verifyCrap` (depends on `check`) fails when a **scoped** method has CRAP > 9.
  - Scope is generated from `config/quality/crap-scope.json` via `tools_pub/quality/module_scope.py`.
  - Scoped paths are concentrated on download/player-policy/local-db; `netease`/`qqmusic` platform
    packages are **not** currently scoped, so new QQ platform code has no CRAP ceiling unless you
    add it to `crap-scope.json`. (You are not required to.)
- Coverage report: `moduleCoverageReport` → `build/reports/crap/coverage.xml`; gated via Jacoco on
  `testDebugUnitTest` (`enableUnitTestCoverage = true` for debug; see feature-library convention).

### 4.4 Tests
- New unit tests go under `modules/platform/src/test/java/moe/ouom/neriplayer/platform/qqmusic/...`
  (mirror the existing netease test tree, e.g. `.../netease/api/auth/NeteaseQrLoginClientTest.kt`,
  `.../netease/api/playback/parser/NeteasePlaybackResponseParserTest.kt`).
- No strict coverage minimum is enforced on the platform module beyond the CRAP gate; but the
  repo clearly expects unit tests for parsers/crypto/codecs (the existing QQ files all have tests).

### 4.5 Exact gradle tasks
```bash
# compile the platform module
./gradlew :platform:compileDebugKotlin
# unit tests for the platform module
./gradlew :platform:testDebugUnitTest
# platform quality gates (run before PR)
./gradlew :platform:verifyCrap :platform:verifyDomainDependencies :platform:lintDebug
# player + download runtime (resolver glue)
./gradlew :playback:runtime:compileDebugUnitTestKotlin :playback:runtime:testDebugUnitTest
./gradlew :download:runtime:testDebugUnitTest
# app (DI + UI)
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest
```
CI workflow `.github/workflows/android_ci.yml` runs `:playback:logic:verifyCrap
:playback:runtime:verifyCrap` on a "Gradle tests" job, plus `:listentogether:verifyCrap
:listentogether:verifyDomainDependencies ... :app:testDebugUnitTest`, and connected
Android tests. Mirror these conventions.

---

## 5. RECOMMENDED FILE LAYOUT (mirror netease/)

All new paths relative to repo root. Base package for platform code:
`moe.ouom.neriplayer.platform.qqmusic`.

### 5.1 Platform module — `modules/platform/src/main/java/moe/ouom/neriplayer/platform/qqmusic/`
```
api/client/QQMusicClient.kt                 // HTTP engine, cookie/session store, named endpoints
api/crypto/QQMusicCrypto.kt                 // g_tk hash / musicu.fcg signing (if needed)
api/auth/QQMusicQrLoginClient.kt            // createSession()/checkLogin() mirror of NeteaseQrLoginClient
api/auth/QQMusicDeviceTokenProvider.kt      // optional device seed (mirror NeteaseYdDeviceTokenProvider)
api/playback/parser/QQMusicPlaybackResponseParser.kt  // PlaybackResult{Success/RequiresLogin/Failure}
api/request/QQMusicPlaylistRequests.kt      // param builders
auth/QQMusicCookieRepository.kt             // EncryptedSharedPreferences-backed cookie repo
auth/QQMusicAuthBundleCodec.kt              // toJson/fromJson for QQMusicAuthBundle
mapping/QQMusicSongMapping.kt               // JSON -> SongItem
mapping/QQMusicPlaylistMapping.kt           // parse remote playlists
mapping/QQMusicArtistJson.kt                // artist summaries / followed artists
playlist/QQMusicRemotePlaylist.kt
playlist/QQMusicPlaylistSync.kt
playlist/QQMusicPlaylistSyncModels.kt
playlist/QQMusicPlaylistBatchAdd.kt
playlist/QQMusicPlaylistSyncRequests.kt
QQMusicPlaylistCacheRepository.kt           // app-scoped playlist cache
```
Move/reuse note: existing anonymous `.../search/api/client/QQMusicSearchApi.kt`,
`.../search/api/codec/QQMusicLyricCodec.kt`, `.../lyrics/repository/QQMusicLyricsRepository.kt`,
and `.../lyrics/matching/QQMusicLyricSelection.kt` stay where they are (lyric matching depends on
them); the new authenticated `QQMusicClient` is a separate collaborator, injected into the search
API and playback resolver.

### 5.2 Model module — `modules/model/src/main/java/moe/ouom/neriplayer/data/model/qqmusic/`
```
auth/QQMusicAuthBundle.kt          // data class(cookies: Map<String,String>, savedAt: Long) + QQ_LOGIN_COOKIE_KEYS
auth/QQMusicQrLoginSession.kt
auth/QQMusicQrLoginCheckResult.kt
auth/QQMusicCookieValidationResult.kt
search/QQMusicModels.kt            // (exists) extend with playback/playlist DTOs
search/QQMusicSongMetadata.kt       // (exists)
playlist/QQMusicPlaylistModels.kt  // remote playlist / sync plan/result
```
> ID-type decision: QQ uses string `mid`. Recommend storing the QQ mid in `SongItem.audioId`
> (String) and/or `matchedSongId: String?`, and base `isQQMusicSource(song)` on that (or on a new
> explicit field). Do NOT try to stuff mids into `SongItem.id: Long`.

### 5.3 Playback runtime — `modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/`
```
resolver/qqmusic/PlayerManagerQQMusicPlayback.kt     // getQQMusicSongUrl(song, ...): SongUrlResult
resolver/qqmusic/PlayerManagerQQMusicQuality.kt     // quality candidate ladder
```
Edit (not new): `media/PlaybackMediaItemFactory.kt` — add `isQQMusicSource(song)` +
`qqMusicCacheKey(...)` branch in `cacheKey(...)`. Edit `PlayerManager.kt` — add
`fun isQQMusicTrack(song)` next to `isBiliTrack`.

### 5.4 Download runtime — `modules/download/runtime/.../download/source/AudioDownloadSourceResolver.kt`
Add `internal suspend fun resolveQQMusic(song, preferredQuality)` + call `QQMusicClient`.
Edit `AudioDownloadManagerAttempt.resolveDownloadSourceForAttempt(...)` to add an `isQQMusic`
branch; add `qqMusicQuality` to `DownloadAudioQualitySelection`.

### 5.5 App module
```
activity/auth/QQMusicQrLoginActivity.kt       // QR scan + poll, returns RESULT_COOKIE JSON
ui/viewmodel/auth/QQMusicAuthViewModel.kt    // cookieFlow, importCookiesFromMap, refreshAuthHealth
ui/screen/tab/settings/auth/SettingsQQMusicAuthDialogs.kt  // login sheet
```

### 5.6 Exact registration edits

**`AppContainer.kt`** (`app/src/main/java/moe/ouom/neriplayer/core/di/AppContainer.kt`):
```kotlin
// + auth repo
val qqMusicCookieRepo by lazy { QQMusicCookieRepository(application) }

// + authenticated client (seed persisted cookies like neteaseClient does)
val qqMusicClient by lazy {
    QQMusicClient(sharedOkHttpClient).also { client ->
        qqMusicCookieRepo.withCurrentCookies { client.setPersistedCookies(it) }
    }
}

// + playlist cache (optional)
val qqMusicPlaylistCacheRepo by lazy { QQMusicPlaylistCacheRepository(application) }

// + cookie observer in initialize()/startCookieObserver()
private fun startQQCookieObserver() {
    qqMusicCookieRepo.cookieFlow.onEach { cookies ->
        qqMusicCookieRepo.withCurrentCookiesIfMatches(cookies) { current ->
            qqMusicClient.setPersistedCookies(current)
        }
    }.launchIn(scope)
}
```
The existing `searchManager` `when` already has a `QQ_MUSIC` branch pointing at the lyrics repo;
keep it (search/lyric path unchanged). If you build a dedicated authenticated search later,
re-point this branch.

**`PlayerRepositoryDependencies.kt`**: add
`val qqMusicClient: QQMusicClient` (and any QQ playback repo), then satisfy it in
`app/.../core/di/player/installPlayerDependencies(...)`.

**Playback resolver switch** in `PlayerManagerUrlExtensions.resolveSongUrl`:
```kotlin
when {
    isYouTubeTrack -> getYouTubeMusicAudioUrl(...)
    isBiliTrack(song) -> getBiliAudioUrl(...)
    isQQMusicTrack(song) -> getQQMusicSongUrl(...)   // NEW
    else -> getNeteaseSongUrl(...)
}
```

**Download switch** in `AudioDownloadManagerAttempt.resolveDownloadSourceForAttempt`:
```kotlin
when {
    isYouTubeMusic -> resolveYouTubeMusic(...)
    isBili -> resolveBili(...)
    isQQMusic -> resolveQQMusic(song, downloadAudioQuality.qqMusicQuality)  // NEW
    else -> resolveNetease(...)
}
```

**Settings/Onboarding UI**: flip `supported = account.platform != SettingsAccountPlatform.QqMusic`
→ always supported; fill the `SettingsAccountPlatform.QqMusic -> Unit` branches in
`SettingsAccountAuthController`; add `SettingsQQMusicAuthDialogs(...)` to
`SettingsAccountAuthDialogs`; add QQ card wiring in `StartupPlatformAccounts.kt`; add a
`QQMusicQrLoginActivity` launcher analogous to `SettingsNeteaseAuthDialogs`.

**Build config**: add `platform-qqmusic-*` entries to `config/quality/domain-dependencies.json`.

---

## 6. Open questions for the implementer (decision points, not blockers)

1. **QQ login mechanism** — QQ Music uses a complex `musicu.fcg` + `g_tk`/`qt` flow and (on PC) a
   QQ-login QR that is not as simple as NetEase's unikey. Decide whether to ship (a) cookie paste
   import only first, (b) a WebView-based QR/login Activity, or (c) full QR client. The NetEase
   `NeteaseQrLoginActivity` + `NeteaseQrLoginClient` pair is the template.
2. **Playback URL endpoint** — QQ requires `cgi/musicu.fcg` `req_0` `GetCgiInfo` /
   `play song` with `songmid` → `vkey`; build `QQMusicPlaybackResponseParser` around the returned
   `req_0.data.murl`. Anonymous vkey quality is limited; login cookies raise it.
3. **ID model** — as noted, QQ mids are strings; decide the `SongItem` field to carry them and how
   `isQQMusicSource(song)` detects them (recommend `channelId`/`album` tag mirroring Bili, or an
   explicit source tag on `SongSearchInfo`).
