package moe.ouom.neriplayer.ui.screen.tab.settings.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CoroutineScope
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.viewmodel.auth.BiliAuthEvent
import moe.ouom.neriplayer.ui.viewmodel.auth.BiliAuthViewModel
import moe.ouom.neriplayer.ui.viewmodel.auth.YouTubeAuthEvent
import moe.ouom.neriplayer.ui.viewmodel.auth.YouTubeAuthViewModel
import moe.ouom.neriplayer.ui.viewmodel.debug.NeteaseAuthEvent
import moe.ouom.neriplayer.ui.viewmodel.debug.NeteaseAuthViewModel

internal class SettingsAuthDialogState(
    val biliTab: MutableIntState,
    val neteaseTab: MutableIntState,
    val youtubeTab: MutableIntState
) {
    var showNeteaseSheet by mutableStateOf(false)
    var showNeteaseSavedCookieDialog by mutableStateOf(false)
    var showNeteaseConfirmDialog by mutableStateOf(false)
    var confirmPhoneMasked by mutableStateOf<String?>(null)
    var showBiliSheet by mutableStateOf(false)
    var showBiliSavedCookieDialog by mutableStateOf(false)
    var showYouTubeSheet by mutableStateOf(false)
    var showYouTubeSavedCookieDialog by mutableStateOf(false)
    var showQqSheet by mutableStateOf(false)
    var loginSuccessTitleRes by mutableStateOf<Int?>(null)
}

internal class SettingsAccountAuthController(
    val neteaseVm: NeteaseAuthViewModel,
    val biliVm: BiliAuthViewModel,
    val youtubeVm: YouTubeAuthViewModel,
    val dialogs: SettingsAuthDialogState,
    private val onInlineMessageChange: State<(String?) -> Unit>
) {
    val actions = SettingsAccountAuthActions(this)

    fun onNeteaseEvent(event: NeteaseAuthEvent) {
        when (event) {
            is NeteaseAuthEvent.ShowSnack -> onInlineMessageChange.value(event.message)
            is NeteaseAuthEvent.AskConfirmSend -> {
                dialogs.confirmPhoneMasked = event.masked
                dialogs.showNeteaseConfirmDialog = true
            }
            NeteaseAuthEvent.LoginSuccess -> {
                dialogs.showNeteaseSavedCookieDialog = false
                dialogs.showNeteaseSheet = false
                dialogs.loginSuccessTitleRes = CoreCommonR.string.settings_netease_login_success
                onInlineMessageChange.value(null)
                neteaseVm.refreshAuthHealth()
            }
        }
    }

    fun onBiliEvent(event: BiliAuthEvent) {
        when (event) {
            is BiliAuthEvent.ShowSnack -> onInlineMessageChange.value(event.message)
            BiliAuthEvent.LoginSuccess -> {
                dialogs.showBiliSavedCookieDialog = false
                dialogs.showBiliSheet = false
                dialogs.loginSuccessTitleRes = CoreCommonR.string.settings_bili_login_success
                onInlineMessageChange.value(null)
                biliVm.refreshAuthHealth()
            }
        }
    }

    fun onYouTubeEvent(event: YouTubeAuthEvent) {
        when (event) {
            is YouTubeAuthEvent.ShowSnack -> onInlineMessageChange.value(event.message)
            YouTubeAuthEvent.LoginSuccess -> {
                dialogs.showYouTubeSavedCookieDialog = false
                dialogs.showYouTubeSheet = false
                dialogs.loginSuccessTitleRes = CoreCommonR.string.settings_youtube_login_success
                onInlineMessageChange.value(null)
                youtubeVm.refreshAuthHealth()
            }
        }
    }

    fun openBiliAtTab(tab: Int) {
        onInlineMessageChange.value(null)
        dialogs.biliTab.intValue = tab
        dialogs.showBiliSheet = true
    }

    fun openNeteaseAtTab(tab: Int) {
        onInlineMessageChange.value(null)
        dialogs.neteaseTab.intValue = tab
        dialogs.showNeteaseSheet = true
    }

    fun openYouTubeAtTab(tab: Int) {
        onInlineMessageChange.value(null)
        dialogs.youtubeTab.intValue = tab
        dialogs.showYouTubeSheet = true
    }

    fun openNeteaseSavedCookieDialog() {
        onInlineMessageChange.value(null)
        dialogs.showNeteaseSavedCookieDialog = true
    }

    fun openBiliSavedCookieDialog() {
        onInlineMessageChange.value(null)
        dialogs.showBiliSavedCookieDialog = true
    }

    fun openYouTubeSavedCookieDialog() {
        onInlineMessageChange.value(null)
        dialogs.showYouTubeSavedCookieDialog = true
    }

    fun openNeteaseSheet() = openNeteaseAtTab(0)
    fun openBiliSheet() = openBiliAtTab(0)
    fun openYouTubeSheet() = openYouTubeAtTab(0)

    fun openQqSheet() {
        onInlineMessageChange.value(null)
        dialogs.showQqSheet = true
    }

    fun dismissQqSheet() { dialogs.showQqSheet = false }

    fun dismissNeteaseSheet() { dialogs.showNeteaseSheet = false }
    fun dismissBiliSheet() { dialogs.showBiliSheet = false }
    fun dismissYouTubeSheet() { dialogs.showYouTubeSheet = false }
    fun dismissNeteaseConfirmDialog() { dialogs.showNeteaseConfirmDialog = false }
    fun dismissNeteaseSavedCookieDialog() { dialogs.showNeteaseSavedCookieDialog = false }
    fun dismissBiliSavedCookieDialog() { dialogs.showBiliSavedCookieDialog = false }
    fun dismissYouTubeSavedCookieDialog() { dialogs.showYouTubeSavedCookieDialog = false }
    fun dismissLoginSuccess() { dialogs.loginSuccessTitleRes = null }

    fun logoutNetease() {
        dismissNeteaseSavedCookieDialog()
        neteaseVm.clearCookies()
    }

    fun logoutBili() {
        dismissBiliSavedCookieDialog()
        biliVm.clearCookies()
    }

    fun logoutYouTube() {
        dismissYouTubeSavedCookieDialog()
        youtubeVm.clearAuth()
    }

    fun logoutQq() {
        dismissQqSheet()
        moe.ouom.neriplayer.core.di.AppContainer.qqMusicCookieRepo.clear()
    }
}

internal class SettingsAccountAuthActions(controller: SettingsAccountAuthController) {
    fun openPlatformLogin(platform: SettingsAccountPlatform) {
        when (platform) {
            SettingsAccountPlatform.Netease -> openNeteaseSheet()
            SettingsAccountPlatform.Bilibili -> openBiliSheet()
            SettingsAccountPlatform.YouTube -> openYouTubeSheet()
            SettingsAccountPlatform.QqMusic -> openQqSheet()
        }
    }

    fun openPlatformSavedAuthorization(platform: SettingsAccountPlatform) {
        when (platform) {
            SettingsAccountPlatform.Netease -> openNeteaseSavedCookieDialog()
            SettingsAccountPlatform.Bilibili -> openBiliSavedCookieDialog()
            SettingsAccountPlatform.YouTube -> openYouTubeSavedCookieDialog()
            SettingsAccountPlatform.QqMusic -> openQqSheet()
        }
    }

    val dismissNeteaseSheet: () -> Unit = controller::dismissNeteaseSheet
    val dismissNeteaseConfirmDialog: () -> Unit = controller::dismissNeteaseConfirmDialog
    val dismissNeteaseSavedCookieDialog: () -> Unit = controller::dismissNeteaseSavedCookieDialog
    val openNeteaseAtTab: (Int) -> Unit = controller::openNeteaseAtTab
    val openNeteaseSavedCookieDialog: () -> Unit = controller::openNeteaseSavedCookieDialog
    val openNeteaseSheet: () -> Unit = controller::openNeteaseSheet
    val logoutNetease: () -> Unit = controller::logoutNetease
    val dismissBiliSheet: () -> Unit = controller::dismissBiliSheet
    val dismissBiliSavedCookieDialog: () -> Unit = controller::dismissBiliSavedCookieDialog
    val openBiliAtTab: (Int) -> Unit = controller::openBiliAtTab
    val openBiliSheet: () -> Unit = controller::openBiliSheet
    val openBiliSavedCookieDialog: () -> Unit = controller::openBiliSavedCookieDialog
    val logoutBili: () -> Unit = controller::logoutBili
    val dismissYouTubeSheet: () -> Unit = controller::dismissYouTubeSheet
    val dismissYouTubeSavedCookieDialog: () -> Unit = controller::dismissYouTubeSavedCookieDialog
    val openYouTubeAtTab: (Int) -> Unit = controller::openYouTubeAtTab
    val openYouTubeSavedCookieDialog: () -> Unit = controller::openYouTubeSavedCookieDialog
    val openYouTubeSheet: () -> Unit = controller::openYouTubeSheet
    val logoutYouTube: () -> Unit = controller::logoutYouTube
    val openQqSheet: () -> Unit = controller::openQqSheet
    val dismissQqSheet: () -> Unit = controller::dismissQqSheet
    val logoutQq: () -> Unit = controller::logoutQq
    val dismissLoginSuccess: () -> Unit = controller::dismissLoginSuccess
}

private class SettingsAuthEventEffects(
    private val controller: SettingsAccountAuthController
) {
    val netease: suspend CoroutineScope.() -> Unit = {
        controller.neteaseVm.events.collect(controller::onNeteaseEvent)
    }
    val bili: suspend CoroutineScope.() -> Unit = {
        controller.biliVm.events.collect(controller::onBiliEvent)
    }
    val youtube: suspend CoroutineScope.() -> Unit = {
        controller.youtubeVm.events.collect(controller::onYouTubeEvent)
    }
}

@Composable
internal fun rememberSettingsAccountAuthController(
    onInlineMessageChange: (String?) -> Unit
): SettingsAccountAuthController {
    val neteaseVm: NeteaseAuthViewModel = viewModel()
    val biliVm: BiliAuthViewModel = viewModel()
    val youtubeVm: YouTubeAuthViewModel = viewModel()
    val biliTab = rememberSaveable { mutableIntStateOf(0) }
    val neteaseTab = rememberSaveable { mutableIntStateOf(0) }
    val youtubeTab = rememberSaveable { mutableIntStateOf(0) }
    val dialogState = remember { SettingsAuthDialogState(biliTab, neteaseTab, youtubeTab) }
    val latestInlineMessageChange = rememberUpdatedState(onInlineMessageChange)
    val controller = remember(neteaseVm, biliVm, youtubeVm, dialogState) {
        SettingsAccountAuthController(
            neteaseVm, biliVm, youtubeVm, dialogState, latestInlineMessageChange
        )
    }
    val effects = SettingsAuthEventEffects(controller)
    LaunchedEffect(neteaseVm, block = effects.netease)
    LaunchedEffect(biliVm, block = effects.bili)
    LaunchedEffect(youtubeVm, block = effects.youtube)
    return controller
}

@Composable
internal fun SettingsAccountAuthDialogs(
    controller: SettingsAccountAuthController,
    inlineMessage: String?,
    onInlineMessageChange: (String?) -> Unit
) {
    SettingsNeteaseAccountAuthDialogs(controller, inlineMessage, onInlineMessageChange)
    SettingsBiliAccountAuthDialogs(controller, inlineMessage, onInlineMessageChange)
    SettingsYouTubeAccountAuthDialogs(controller, inlineMessage, onInlineMessageChange)
    SettingsQqAccountAuthDialogs(controller, inlineMessage, onInlineMessageChange)
    SettingsAccountLoginSuccessDialog(controller)
}

@Composable
private fun SettingsNeteaseAccountAuthDialogs(
    controller: SettingsAccountAuthController,
    inlineMessage: String?,
    onInlineMessageChange: (String?) -> Unit
) {
    val dialogs = controller.dialogs
    SettingsNeteaseAuthDialogs(
        showSheet = dialogs.showNeteaseSheet,
        initialTab = dialogs.neteaseTab.intValue,
        onDismissSheet = controller.actions.dismissNeteaseSheet,
        inlineMsg = inlineMessage,
        onInlineMsgChange = onInlineMessageChange,
        showConfirmDialog = dialogs.showNeteaseConfirmDialog,
        confirmPhoneMasked = dialogs.confirmPhoneMasked,
        onDismissConfirmDialog = controller.actions.dismissNeteaseConfirmDialog,
        vm = controller.neteaseVm,
        showSavedCookieDialog = dialogs.showNeteaseSavedCookieDialog,
        onDismissSavedCookieDialog = controller.actions.dismissNeteaseSavedCookieDialog,
        onOpenSheetAtTab = controller.actions.openNeteaseAtTab,
        onLogout = controller.actions.logoutNetease,
        onBrowserLogin = null
    )
}

@Composable
private fun SettingsBiliAccountAuthDialogs(
    controller: SettingsAccountAuthController,
    inlineMessage: String?,
    onInlineMessageChange: (String?) -> Unit
) {
    val dialogs = controller.dialogs
    SettingsBiliAuthDialogs(
        showSheet = dialogs.showBiliSheet,
        initialTab = dialogs.biliTab.intValue,
        onDismissSheet = controller.actions.dismissBiliSheet,
        inlineMsg = inlineMessage,
        onInlineMsgChange = onInlineMessageChange,
        vm = controller.biliVm,
        showSavedCookieDialog = dialogs.showBiliSavedCookieDialog,
        onDismissSavedCookieDialog = controller.actions.dismissBiliSavedCookieDialog,
        onOpenSheetAtTab = controller.actions.openBiliAtTab,
        onLogout = controller.actions.logoutBili,
        onBrowserLogin = null
    )
}

@Composable
private fun SettingsYouTubeAccountAuthDialogs(
    controller: SettingsAccountAuthController,
    inlineMessage: String?,
    onInlineMessageChange: (String?) -> Unit
) {
    val dialogs = controller.dialogs
    SettingsYouTubeAuthDialogs(
        showSheet = dialogs.showYouTubeSheet,
        initialTab = dialogs.youtubeTab.intValue,
        onDismissSheet = controller.actions.dismissYouTubeSheet,
        inlineMsg = inlineMessage,
        onInlineMsgChange = onInlineMessageChange,
        vm = controller.youtubeVm,
        showSavedCookieDialog = dialogs.showYouTubeSavedCookieDialog,
        onDismissSavedCookieDialog = controller.actions.dismissYouTubeSavedCookieDialog,
        onOpenSheetAtTab = controller.actions.openYouTubeAtTab,
        onLogout = controller.actions.logoutYouTube
    )
}

@Composable
private fun SettingsAccountLoginSuccessDialog(controller: SettingsAccountAuthController) {
    val dialogs = controller.dialogs
    val loginTitleRes = dialogs.loginSuccessTitleRes
    if (loginTitleRes != null) {
        LoginSuccessDialog(
            title = stringResource(loginTitleRes),
            onDismiss = controller.actions.dismissLoginSuccess
        )
    }
}

@Composable
private fun SettingsQqAccountAuthDialogs(
    controller: SettingsAccountAuthController,
    inlineMessage: String?,
    onInlineMessageChange: (String?) -> Unit
) {
    val dialogs = controller.dialogs
    if (!dialogs.showQqSheet) return
    SettingsCookieLoginSheet(
        title = stringResource(CoreCommonR.string.settings_qq_music),
        initialTab = 1,
        inlineMsg = inlineMessage,
        onInlineMsgChange = onInlineMessageChange,
        onDismiss = controller.actions.dismissQqSheet,
        browserTabLabel = stringResource(CoreCommonR.string.login_paste_cookie),
        browserButtonLabel = stringResource(CoreCommonR.string.login_paste_cookie),
        browserHintContent = {
            androidx.compose.material3.Text(
                stringResource(CoreCommonR.string.login_paste_cookie_hint)
            )
        },
        cookieLabel = stringResource(CoreCommonR.string.login_paste_cookie_hint),
        onBrowserLogin = { },
        onSaveCookie = { rawCookie ->
            if (rawCookie.isBlank()) {
                onInlineMessageChange(null)
            } else {
                val ok = moe.ouom.neriplayer.core.di.AppContainer.qqMusicCookieRepo
                    .saveCookies(parseQqCookieMap(rawCookie))
                onInlineMessageChange(null)
                if (ok) dialogs.showQqSheet = false
            }
        }
    )
}

private fun parseQqCookieMap(raw: String): Map<String, String> {
    val result = linkedMapOf<String, String>()
    raw.split(';').forEach { pair ->
        val key = pair.substringBefore('=', "").trim()
        val value = pair.substringAfter('=', "").trim()
        if (key.isNotEmpty() && value.isNotEmpty()) {
            result[key] = value
        }
    }
    return result
}
