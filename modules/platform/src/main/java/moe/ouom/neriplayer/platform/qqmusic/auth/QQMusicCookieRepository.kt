@file:Suppress("DEPRECATION")

package moe.ouom.neriplayer.platform.qqmusic.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.common.storage.SecurePreferencesEvent
import moe.ouom.neriplayer.common.storage.SecurePreferencesOpener
import moe.ouom.neriplayer.common.storage.VolatileSharedPreferences
import moe.ouom.neriplayer.data.model.qqmusic.auth.QQ_LOGIN_COOKIE_KEYS
import moe.ouom.neriplayer.data.model.qqmusic.auth.QQMusicAuthBundle
import moe.ouom.neriplayer.data.model.qqmusic.auth.QQMusicCookieValidationResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val QQ_MUSIC_AUTH_PREFS = "qqmusic_auth_secure_prefs"
private const val KEY_QQ_MUSIC_AUTH_BUNDLE = "qqmusic_auth_bundle"

private val QQ_MUSIC_COOKIE_NAME_REGEX = Regex("^[!#\$%&'*+.^_`|~0-9A-Za-z-]+$")

/** 校验并清洗 Cookie；拒绝空白名、非法字符、控制字符与包含分号的值。 */
fun validateAndSanitizeQQMusicCookies(
    cookies: Map<String, String>
): QQMusicCookieValidationResult {
    val sanitized = linkedMapOf<String, String>()
    val rejected = linkedSetOf<String>()

    cookies.forEach { (rawKey, rawValue) ->
        val key = rawKey.trim()
        val value = rawValue.trim()
        val rejectedKey = key.ifBlank { "<blank>" }
        when {
            key.isBlank() -> rejected += rejectedKey
            !QQ_MUSIC_COOKIE_NAME_REGEX.matches(key) -> rejected += rejectedKey
            value.isBlank() -> rejected += rejectedKey
            value.any { it.isISOControl() } -> rejected += rejectedKey
            ';' in value -> rejected += rejectedKey
            else -> sanitized[key] = value
        }
    }

    return QQMusicCookieValidationResult(
        sanitizedCookies = sanitized,
        rejectedKeys = rejected.toList()
    )
}

/**
 * QQ 音乐登录 Cookie 的加密持久化。与 NeteaseCookieRepository 同一套 SecurePreferences 容错策略。
 */
class QQMusicCookieRepository(private val context: Context) {
    private val mutationLock = Any()
    private var encryptedPrefs: SharedPreferences
    private val _authFlow: MutableStateFlow<QQMusicAuthBundle>
    private val _cookieFlow: MutableStateFlow<Map<String, String>>

    val cookieFlow: StateFlow<Map<String, String>>
        get() = _cookieFlow.asStateFlow()

    init {
        encryptedPrefs = openEncryptedPrefsWithRecovery()
        val initialBundle = loadAuthBundle()
        _authFlow = MutableStateFlow(initialBundle)
        _cookieFlow = MutableStateFlow(initialBundle.cookies)
    }

    fun getCookiesOnce(): Map<String, String> = _cookieFlow.value

    fun <T> withCurrentCookies(action: (Map<String, String>) -> T): T {
        return synchronized(mutationLock) { action(_cookieFlow.value) }
    }

    fun validateCookies(cookies: Map<String, String>): QQMusicCookieValidationResult {
        return validateAndSanitizeQQMusicCookies(cookies)
    }

    fun saveCookies(
        cookies: Map<String, String>,
        savedAt: Long = System.currentTimeMillis()
    ): Boolean {
        return synchronized(mutationLock) {
            val validation = validateCookies(cookies)
            if (!validation.isAccepted) {
                NPLogger.w(
                    "NERI-QQCookieRepo",
                    "Rejected invalid QQ Music cookies. rejectedKeys=${validation.rejectedKeys.joinToString()}"
                )
                return@synchronized false
            }
            val normalized = QQMusicAuthBundle(
                cookies = validation.sanitizedCookies,
                savedAt = savedAt
            ).normalized(savedAt = savedAt)
            writeAuthBundle(normalized)
            _authFlow.value = normalized
            _cookieFlow.value = normalized.cookies
            NPLogger.d(
                "NERI-QQCookieRepo",
                "Saved QQ Music cookies: keys=${normalized.cookies.keys.joinToString()}"
            )
            true
        }
    }

    fun clear() {
        synchronized(mutationLock) {
            writeAuthBundle(null)
            val cleared = QQMusicAuthBundle()
            _authFlow.value = cleared
            _cookieFlow.value = cleared.cookies
            NPLogger.d("NERI-QQCookieRepo", "Cleared QQ Music cookies.")
        }
    }

    private fun loadAuthBundle(): QQMusicAuthBundle {
        val raw = runCatching {
            encryptedPrefs.getString(KEY_QQ_MUSIC_AUTH_BUNDLE, null).orEmpty()
        }.getOrElse { error ->
            NPLogger.w("NERI-QQCookieRepo", "Failed to read QQ secure prefs, recovering.", error)
            recoverUnreadableStorage(error)
            ""
        }
        if (raw.isBlank()) return QQMusicAuthBundle()
        return QQMusicAuthBundle.fromJson(raw)
    }

    private fun openEncryptedPrefsWithRecovery(): SharedPreferences = SecurePreferencesOpener.open(
        name = QQ_MUSIC_AUTH_PREFS,
        create = { createEncryptedPrefs() },
        delete = { clearEncryptedStorage() },
        report = { event: SecurePreferencesEvent, error: Throwable ->
            NPLogger.w("NERI-QQCookieRepo", "QQ secure prefs $event", error)
        }
    )

    private fun recoverUnreadableStorage(error: Throwable) {
        encryptedPrefs = if (SecurePreferencesOpener.isKeystoreUnavailable(error)) {
            VolatileSharedPreferences.shared(QQ_MUSIC_AUTH_PREFS)
        } else {
            clearEncryptedStorage()
            openEncryptedPrefsWithRecovery()
        }
    }

    private fun writeAuthBundle(bundle: QQMusicAuthBundle?): Boolean = runCatching {
        encryptedPrefs.edit {
            if (bundle == null) remove(KEY_QQ_MUSIC_AUTH_BUNDLE)
            else putString(KEY_QQ_MUSIC_AUTH_BUNDLE, bundle.toJson())
        }
    }.onFailure { error ->
        NPLogger.w("NERI-QQCookieRepo", "Failed to write QQ secure prefs", error)
    }.isSuccess

    private fun clearEncryptedStorage() {
        runCatching {
            context.deleteSharedPreferences(QQ_MUSIC_AUTH_PREFS)
        }.onFailure { error ->
            NPLogger.w("NERI-QQCookieRepo", "Failed to delete corrupted QQ secure prefs.", error)
        }
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            QQ_MUSIC_AUTH_PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    @Suppress("unused")
    private fun loginCookieKeys(): List<String> = QQ_LOGIN_COOKIE_KEYS
}
