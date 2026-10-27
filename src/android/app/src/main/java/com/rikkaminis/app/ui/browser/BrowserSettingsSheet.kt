package com.rikkaminis.app.ui.browser

import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rikkaminis.app.R
import com.rikkaminis.app.browser.BrowserTabPool
import com.rikkaminis.app.browser.UserAgentProfile
import com.rikkaminis.app.logging.AppLogger
import kotlinx.coroutines.launch
import com.rikkaminis.app.ui.components.MinisTextButton
import com.rikkaminis.app.ui.components.sanitizeSingleLineInput
import com.rikkaminis.app.ui.util.bringIntoViewOnFocus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserSettingsSheet(
    tabPool: BrowserTabPool,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE) }

    // [da1030-B] Seed from the pool's live flow and key on it: an agent
    // `set_user_agent` now routes through the pool, and a keyless
    // `remember { prefs.getString(...) }` snapshot kept rendering the old
    // radio selection after that switch. The pool restores the same prefs key
    // at construction, so its initial value is the persisted profile.
    val poolProfile by tabPool.currentUserAgentProfile.collectAsState()
    var selectedProfile by remember(poolProfile) { mutableStateOf(poolProfile) }
    var customUA by remember { mutableStateOf(prefs.getString("custom_user_agent", "") ?: "") }
    var showClearConfirm by remember { mutableStateOf(false) }
    var idleTimeoutText by remember {
        mutableStateOf(
            prefs.getInt(
                BrowserTabPool.PREF_IDLE_TIMEOUT_MINUTES,
                BrowserTabPool.DEFAULT_IDLE_TIMEOUT_MINUTES,
            ).toString()
        )
    }
    val coroutineScope = rememberCoroutineScope()

    // Viewport state — bound to the pool's flows so changes from other surfaces
    // (e.g. the agent calling set_viewport) reflect in this sheet immediately.
    //
    // [da1029-E] Two sources, one meaning. The pool resolves a tab's viewport as
    // session override > global custom > UA default, but this sheet only ever
    // subscribed to the GLOBAL pair: an agent `set_viewport` (session-scoped)
    // left the panel rendering "Using UA default <session size>", with empty
    // width/height fields and the Default radio selected. Subscribe to both and
    // derive the effective pair with the pool's own priority (both components
    // must be > 0 for a source to win — mirroring resolvedViewportSize()).
    val customWidthFromPool by tabPool.customViewportWidth.collectAsState()
    val customHeightFromPool by tabPool.customViewportHeight.collectAsState()
    val sessionWidthFromPool by tabPool.sessionViewportWidth.collectAsState()
    val sessionHeightFromPool by tabPool.sessionViewportHeight.collectAsState()
    val sessionViewportActive = sessionWidthFromPool > 0 && sessionHeightFromPool > 0
    val customViewportActive = customWidthFromPool > 0 && customHeightFromPool > 0
    val effectiveWidthFromPool = when {
        sessionViewportActive -> sessionWidthFromPool
        customViewportActive -> customWidthFromPool
        else -> 0
    }
    val effectiveHeightFromPool = when {
        sessionViewportActive -> sessionHeightFromPool
        customViewportActive -> customHeightFromPool
        else -> 0
    }
    var viewportWidthText by remember(effectiveWidthFromPool) {
        mutableStateOf(if (effectiveWidthFromPool > 0) effectiveWidthFromPool.toString() else "")
    }
    var viewportHeightText by remember(effectiveHeightFromPool) {
        mutableStateOf(if (effectiveHeightFromPool > 0) effectiveHeightFromPool.toString() else "")
    }
    val isCustomViewportActive = effectiveWidthFromPool > 0 && effectiveHeightFromPool > 0
    // Bug 1: decouple radio selection from pool state. The radio reflects what the
    // user picked *in the sheet*; Apply (or a preset tap) is what writes to the
    // pool. Without this, tapping Custom with empty width/height fields was a
    // no-op visually because isCustomViewportActive stayed false.
    var viewportMode by remember(isCustomViewportActive) {
        mutableStateOf(if (isCustomViewportActive) ViewportMode.CUSTOM else ViewportMode.DEFAULT)
    }
    var showCustomViewportEditor by remember(isCustomViewportActive) {
        mutableStateOf(isCustomViewportActive)
    }

    // Cookie filter (mirrors iOS `.searchable("Filter by domain")`).
    var cookieFilterText by remember { mutableStateOf("") }

    // [fix/da1022-G] Refresh tick for the cookie section. hasCookies/domains were
    // computed from plain locals with no State write on delete/clear, so nothing
    // triggered recomposition and the list + Clear-All button stayed stale.
    // Declared at the top level so the clear-all AlertDialog below can bump it.
    var cookieRefreshTick by remember { mutableStateOf(0) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // [T-android-browser-settings-keyboard-overlap] imePadding
                // shrinks the sheet's content frame when the keyboard
                // opens; verticalScroll lets the user (or the focused-
                // field bring-into-view) scroll the now-shorter content
                // so the focused TextField isn't behind the keyboard.
                // Previously the Column was a fixed Column with no
                // scroll, so Width/Height/idle-timeout fields below the
                // viewport stayed glued and the keyboard ate them.
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
                .navigationBarsPadding(),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.browser_settings_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                MinisTextButton(onClick = onDismiss) { Text(stringResource(R.string.browser_settings_done)) }
            }

            Spacer(Modifier.height(16.dp))

            // ── User Agent ──
            Text(
                stringResource(R.string.browser_settings_user_agent),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))

            val notSetPlaceholder = stringResource(R.string.browser_settings_ua_not_set)
            for (profile in UserAgentProfile.entries) {
                val label = stringResource(when (profile) {
                    UserAgentProfile.MOBILE_CHROME -> R.string.browser_settings_ua_mobile_chrome
                    UserAgentProfile.DESKTOP_CHROME -> R.string.browser_settings_ua_desktop_chrome
                    UserAgentProfile.CUSTOM -> R.string.browser_settings_ua_custom
                })
                val uaSubtitle = displayUA(profile, customUA, notSetPlaceholder)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selectedProfile == profile,
                        onClick = {
                            selectedProfile = profile
                            prefs.edit()
                                .putString("user_agent_profile", profile.name)
                                .apply()
                            tabPool.setUserAgentFromUI(profile, if (profile == UserAgentProfile.CUSTOM) customUA else null)
                        },
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            uaSubtitle,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (selectedProfile == UserAgentProfile.CUSTOM) {
                OutlinedTextField(
                    value = customUA,
                    onValueChange = {
                        customUA = it
                        prefs.edit().putString("custom_user_agent", it).apply()
                        // [dv23-B4 B-05] Keep the pool's copy in step without
                        // reloading every open tab on each keystroke: Apply (or a
                        // preset tap) stays the moment live tabs change, but the
                        // stored copy must not lag behind the field.
                        tabPool.stageCustomUserAgent(it)
                    },
                    label = { Text(stringResource(R.string.browser_settings_custom_ua_label)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    singleLine = false,
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                MinisTextButton(onClick = {
                    tabPool.setUserAgentFromUI(UserAgentProfile.CUSTOM, customUA)
                }) {
                    Text(stringResource(R.string.browser_settings_apply))
                }
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // ── Web Viewport (mirrors iOS BrowserManagementView.viewportSection) ──
            ViewportSection(
                tabPool = tabPool,
                selectedProfile = selectedProfile,
                viewportMode = viewportMode,
                isCustomActive = isCustomViewportActive,
                widthText = viewportWidthText,
                heightText = viewportHeightText,
                showEditor = showCustomViewportEditor,
                onWidthChange = { viewportWidthText = it.filter { ch -> ch.isDigit() }.take(5) },
                onHeightChange = { viewportHeightText = it.filter { ch -> ch.isDigit() }.take(5) },
                // [da1029-E] Every action here writes the GLOBAL viewport, but a
                // session override shadows it — so without clearing the session
                // pair first, "Default" left the tabs at the agent's
                // set_viewport size and the panel kept reporting it as custom
                // (the actions looked like no-ops). Clear the session override
                // before writing the global value, so the resolved viewport is
                // the one the user just picked.
                onSelectDefault = {
                    viewportMode = ViewportMode.DEFAULT
                    AppLogger.info("BrowserSettings", "viewportMode → DEFAULT")
                    coroutineScope.launch {
                        tabPool.resetSessionViewport()
                        tabPool.setGlobalViewport(0, 0)
                        AppLogger.info("BrowserSettings", "viewport set to UA default")
                    }
                    viewportWidthText = ""
                    viewportHeightText = ""
                    showCustomViewportEditor = false
                },
                onSelectCustom = {
                    viewportMode = ViewportMode.CUSTOM
                    AppLogger.info("BrowserSettings", "viewportMode → CUSTOM")
                    showCustomViewportEditor = true
                    val w = viewportWidthText.toIntOrNull()
                    val h = viewportHeightText.toIntOrNull()
                    if (w != null && h != null && w > 0 && h > 0) {
                        coroutineScope.launch {
                            tabPool.resetSessionViewport()
                            tabPool.setGlobalViewport(w.coerceIn(VIEWPORT_MIN, VIEWPORT_MAX), h.coerceIn(VIEWPORT_MIN, VIEWPORT_MAX))
                            AppLogger.info("BrowserSettings", "viewport set to ${w}x$h (custom)")
                        }
                    }
                },
                onApply = {
                    val w = viewportWidthText.toIntOrNull()
                    val h = viewportHeightText.toIntOrNull()
                    if (w != null && h != null && w > 0 && h > 0) {
                        val cw = w.coerceIn(VIEWPORT_MIN, VIEWPORT_MAX)
                        val ch = h.coerceIn(VIEWPORT_MIN, VIEWPORT_MAX)
                        viewportWidthText = cw.toString()
                        viewportHeightText = ch.toString()
                        viewportMode = ViewportMode.CUSTOM
                        coroutineScope.launch {
                            tabPool.resetSessionViewport()
                            tabPool.setGlobalViewport(cw, ch)
                            AppLogger.info("BrowserSettings", "viewport applied ${cw}x$ch")
                        }
                    }
                },
                onPreset = { w, h ->
                    viewportWidthText = w.toString()
                    viewportHeightText = h.toString()
                    viewportMode = ViewportMode.CUSTOM
                    coroutineScope.launch {
                        tabPool.resetSessionViewport()
                        tabPool.setGlobalViewport(w, h)
                        AppLogger.info("BrowserSettings", "viewport preset ${w}x$h")
                    }
                },
            )

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // ── Idle Tab Eviction ──
            Text(
                stringResource(R.string.browser_settings_idle_timeout),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.browser_settings_idle_timeout_desc) + " " +
                    "(default ${BrowserTabPool.DEFAULT_IDLE_TIMEOUT_MINUTES}, range " +
                    "${BrowserTabPool.MIN_IDLE_TIMEOUT_MINUTES}–${BrowserTabPool.MAX_IDLE_TIMEOUT_MINUTES}).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = idleTimeoutText,
                    onValueChange = { new -> idleTimeoutText = new.filter { it.isDigit() }.take(3) },
                    label = { Text(stringResource(R.string.browser_settings_minutes_label)) },
                    singleLine = true,
                    // [T-android-browser-settings-keyboard-overlap] this
                    // field sits near the bottom of the sheet — without
                    // scroll-into-view it sits behind the soft keyboard
                    // every time the user taps it.
                    modifier = Modifier.weight(1f).bringIntoViewOnFocus(),
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.size(8.dp))
                MinisTextButton(
                    onClick = {
                        val minutes = idleTimeoutText.toIntOrNull()
                            ?: BrowserTabPool.DEFAULT_IDLE_TIMEOUT_MINUTES
                        tabPool.setIdleTimeoutMinutes(minutes)
                        // Reflect clamping back into the field.
                        idleTimeoutText = tabPool.idleTimeoutMinutes.toString()
                    },
                ) { Text(stringResource(R.string.browser_settings_apply)) }
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // ── Cookies & Website Data ──
            Text(
                stringResource(R.string.browser_settings_cookies_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))

            // [fix/da1022-G] hasCookies was a plain local var; keying on a tick state
            // is what makes delete/clear actually refresh the section.
            val cookieManager = remember { CookieManager.getInstance() }
            val historyStore = remember { com.rikkaminis.app.browser.BrowserHistoryStore.getInstance(context) }
            // [deep-audit-1039 B-07] ...and the refresh signal for the DOMAIN
            // list is the STORE's, not this sheet's. The sheet's own tick only
            // moves when a cookie is deleted from THIS panel, but the domains
            // are history domains: switching UA (or Viewport) in this panel
            // reloads the active tab, BrowserUseManager.onPageFinished records
            // the visit and historyStore.revision advances — with the list
            // keyed on the panel tick alone it kept rendering the pre-reload
            // snapshot until the sheet was closed and reopened. Subscribe to
            // the store's counter exactly like BrowserHistorySheet ([dv15-B3])
            // does and feed it into both remembers below. (Declared before them
            // on purpose: a `by` delegate is a local val, not a hoisted symbol.)
            val historyRevision by historyStore.revision.collectAsState()
            val hasCookies = remember(cookieRefreshTick, historyRevision) { cookieManager.hasCookies() }
            // [fix/da1022-G] Real cookie domains cannot be enumerated on Android
            // (getCookie(url) needs a URL; hasCookies() is just a boolean), so the
            // list is the history domains probed against both schemes — exactly the
            // domains the per-domain delete below can clear. Domains holding only
            // HttpOnly cookies are invisible to getCookie and drop out; the
            // `hasCookies -> cookies_stored` empty-state branch covers that case.
            // ponytail: 域名列表 = 历史域 × 双 scheme 可读 cookie 探查（Android 无枚举 API）
            // | 天花板: 仅持 HttpOnly cookie 的域不出现在列表（per-domain 删除本来就碰不到它们）
            // | 升级触发: WebView 暴露 cookie 枚举 API，或用户要求列出/删除 HttpOnly 域
            val allDomains = remember(hasCookies, cookieRefreshTick, historyRevision) {
                if (!hasCookies) {
                    emptyList()
                } else {
                    historyStore.uniqueDomains().filter { domain ->
                        !cookieManager.getCookie("https://$domain").isNullOrBlank() ||
                            !cookieManager.getCookie("http://$domain").isNullOrBlank()
                    }
                }
            }
            val domains = remember(allDomains, cookieFilterText) {
                if (cookieFilterText.isBlank()) allDomains
                else allDomains.filter { it.contains(cookieFilterText.trim(), ignoreCase = true) }
            }

            if (allDomains.isNotEmpty()) {
                OutlinedTextField(
                    value = cookieFilterText,
                    onValueChange = { cookieFilterText = sanitizeSingleLineInput(it) },
                    label = { Text(stringResource(R.string.browser_settings_filter_by_domain)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    textStyle = MaterialTheme.typography.bodySmall,
                )
            }

            if (domains.isEmpty()) {
                Text(
                    when {
                        cookieFilterText.isNotBlank() -> stringResource(R.string.browser_settings_no_domains_match, cookieFilterText)
                        hasCookies -> stringResource(R.string.browser_settings_cookies_stored)
                        else -> stringResource(R.string.browser_settings_no_cookies)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(modifier = Modifier.height((domains.size.coerceAtMost(6) * 44).dp)) {
                    items(domains) { domain ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                domain,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = {
                                    // [T7-M3] setCookie(裸 host, "") was a silent
                                    // no-op: the first argument must be a URL, and an
                                    // empty value cannot delete an existing cookie
                                    // (RFC 6265 requires same-name + Max-Age=0).
                                    // Enumerate the readable names and expire each.
                                    // HttpOnly cookies are invisible to getCookie, so
                                    // Android offers no way to expire those per domain.
                                    val cm = CookieManager.getInstance()
                                    // [fix/da1022-G] History domains are URI hosts
                                    // (no scheme) — expire both variants, or a
                                    // cookie set over http survives the deletion.
                                    val candidateUrls = if (domain.startsWith("http")) {
                                        listOf(domain)
                                    } else {
                                        listOf("https://$domain", "http://$domain")
                                    }
                                    for (url in candidateUrls) {
                                        val names = cm.getCookie(url).orEmpty()
                                            .split(';')
                                            .mapNotNull { part ->
                                                part.substringBefore('=').trim().takeIf { it.isNotEmpty() }
                                            }
                                        names.forEach { cm.setCookie(url, "$it=; Max-Age=0; Path=/") }
                                    }
                                    cm.flush()
                                    cookieRefreshTick++
                                },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.browser_settings_clear_cookies_for, domain),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            MinisTextButton(
                onClick = { showClearConfirm = true },
                enabled = hasCookies,
            ) {
                Text(stringResource(R.string.browser_settings_clear_all_cookies), color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.browser_settings_clear_all_dialog_title)) },
            text = { Text(stringResource(R.string.browser_settings_clear_all_dialog_message)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    // [fix/da1022-G] removeAllCookies(null) left no flush and no
                    // refresh: use the callback form to persist + bump the tick,
                    // and bump immediately for the (possibly synchronous) case.
                    val cm = CookieManager.getInstance()
                    cm.removeAllCookies {
                        cm.flush()
                        cookieRefreshTick++
                    }
                    WebStorage.getInstance().deleteAllData()
                    showClearConfirm = false
                    cookieRefreshTick++
                }) {
                    Text(stringResource(R.string.browser_settings_clear), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** Local UI state for the viewport radios — separate from the pool's
 *  customViewportWidth/Height because the radio reflects the user's *intent*
 *  (which option is highlighted), and the pool reflects the *committed*
 *  override. Tapping Custom flips the radio immediately even if no width/
 *  height has been entered yet. */
private enum class ViewportMode { DEFAULT, CUSTOM }

/** UA list subtitle: full UA string in monospace, or "Not set" when the
 *  Custom slot is empty. Mirrors the iOS sheet which shows the full UA below
 *  each radio so the user can verify exactly what's being sent. */
private fun displayUA(profile: UserAgentProfile, customUA: String, notSetPlaceholder: String): String =
    when (profile) {
        UserAgentProfile.CUSTOM -> customUA.ifBlank { notSetPlaceholder }
        else -> profile.userAgentString.orEmpty()
    }

// ── Viewport ──────────────────────────────────────────────────────────────

/** Min / max permitted viewport dimensions. Wider than typical phone screens
 *  but bounded so the user can't accidentally enter `99999` and DOS the
 *  WebView's layout pass. Mirrors a similar implicit clamp on iOS where the
 *  user types into a 72pt-wide field. */
private const val VIEWPORT_MIN = 200
private const val VIEWPORT_MAX = 4096

/** UA-vs-viewport breakpoint copied from iOS BrowserManagementView. Sites
 *  using UA-sniffing will hand back the wrong layout when these cross. */
private const val VIEWPORT_UA_BREAKPOINT = 768

private val viewportPresets = listOf(
    Triple("Phone", 412, 915),
    Triple("Phone Pro", 430, 932),
    Triple("Tablet", 820, 1180),
    Triple("Laptop", 1280, 800),
    Triple("Desktop", 1440, 900),
    Triple("Full HD", 1920, 1080),
)

@Composable
private fun ViewportSection(
    tabPool: BrowserTabPool,
    selectedProfile: UserAgentProfile,
    viewportMode: ViewportMode,
    isCustomActive: Boolean,
    widthText: String,
    heightText: String,
    showEditor: Boolean,
    onWidthChange: (String) -> Unit,
    onHeightChange: (String) -> Unit,
    onSelectDefault: () -> Unit,
    onSelectCustom: () -> Unit,
    onApply: () -> Unit,
    onPreset: (Int, Int) -> Unit,
) {
    Text(
        "Web Viewport",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(8.dp))

    // Default row
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = viewportMode == ViewportMode.DEFAULT,
            onClick = onSelectDefault,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.browser_ua_default), style = MaterialTheme.typography.bodyMedium)
            val (uaW, uaH) = selectedProfile.viewportSize
            Text(
                "${uaW} × $uaH",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // Custom row
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = viewportMode == ViewportMode.CUSTOM,
            onClick = onSelectCustom,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.browser_ua_custom), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.browser_ua_custom_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showEditor) {
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = widthText,
                onValueChange = onWidthChange,
                label = { Text(stringResource(R.string.browser_viewport_width)) },
                singleLine = true,
                // [T-android-browser-settings-keyboard-overlap] focus →
                // scroll-into-view so the soft keyboard doesn't cover
                // the Custom viewport editor.
                modifier = Modifier.width(110.dp).bringIntoViewOnFocus(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Spacer(Modifier.size(8.dp))
            Text("×", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(8.dp))
            OutlinedTextField(
                value = heightText,
                onValueChange = onHeightChange,
                label = { Text(stringResource(R.string.browser_viewport_height)) },
                singleLine = true,
                // [T-android-browser-settings-keyboard-overlap] focus →
                // scroll-into-view; mirrors the Width field.
                modifier = Modifier.width(110.dp).bringIntoViewOnFocus(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Spacer(Modifier.size(8.dp))
            MinisTextButton(onClick = onApply) { Text(stringResource(R.string.browser_settings_apply)) }
        }

        // UA mismatch banner — same 768px breakpoint as iOS.
        val warning = uaMismatchWarning(selectedProfile, widthText)
        if (warning != null) {
            Spacer(Modifier.height(8.dp))
            UaMismatchBanner(warning)
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "Quick Set",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for ((label, w, h) in viewportPresets) {
                val active = widthText.toIntOrNull() == w && heightText.toIntOrNull() == h
                val bg = if (active) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
                val fg = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp)),
                    color = bg,
                    onClick = { onPreset(w, h) },
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodySmall,
                            color = fg,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "$w × $h",
                            style = MaterialTheme.typography.labelSmall,
                            color = fg,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    val (resW, resH) = tabPool.resolvedViewportSize()
    Text(
        if (isCustomActive) {
            "Using custom viewport $resW × $resH. Tap Default to revert to the UA default."
        } else {
            "Using UA default $resW × $resH. Set a custom size to override."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun uaMismatchWarning(profile: UserAgentProfile, widthText: String): String? {
    val width = widthText.toIntOrNull() ?: return null
    if (width <= 0) return null
    return when (profile) {
        UserAgentProfile.MOBILE_CHROME, UserAgentProfile.CUSTOM -> {
            if (width >= VIEWPORT_UA_BREAKPOINT) {
                "Viewport ≥${VIEWPORT_UA_BREAKPOINT}px while UA is Mobile — sites may serve a desktop layout that renders awkwardly. Consider switching UA to Desktop."
            } else null
        }
        UserAgentProfile.DESKTOP_CHROME -> {
            if (width < VIEWPORT_UA_BREAKPOINT) {
                "Viewport <${VIEWPORT_UA_BREAKPOINT}px while UA is Desktop — sites may serve a mobile layout that renders awkwardly. Consider switching UA to Mobile."
            } else null
        }
    }
}

@Composable
private fun UaMismatchBanner(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f))
            .padding(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.tertiary,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
