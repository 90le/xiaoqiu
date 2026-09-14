package com.xiaoqiu.ui.terminal

import com.xiaoqiu.R
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardTab
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Eject
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaoqiu.sandbox.TerminalSession
import com.xiaoqiu.terminal.MinisOpenUrlBroker
import com.xiaoqiu.ui.terminal.canvas.TerminalNativeViewCompose
import com.xiaoqiu.ui.terminal.canvas.TerminalInputView
import com.xiaoqiu.ui.terminal.canvas.rememberTerminalInputController
import com.xiaoqiu.ui.terminal.emulator.TerminalEmulator
import kotlinx.coroutines.launch

// iOS-matched palette
private val TerminalBg = Color(0xFF000000)
private val TerminalFg = Color(0xFFD4D4D4)
private val TerminalGreen = Color(0xFF34C759)
private val AccessoryBg = Color(0xFF1F1F1F)
private val AccButtonBg = Color(0xFF404040)
private val AccButtonActive = Color(0xFF007AFF)
private val TopButtonBg = Color(0xFF2C2C2E)

@Composable
fun TerminalScreen(
    terminalSession: TerminalSession?,
    onBack: () -> Unit,
    initCommand: String? = null,
    /**
     * When non-null, binds this terminal to the given chat session —
     * TerminalSession.start() will chdir into /var/minis and pick up the
     * session's env vars (mirrors iOS "Open Terminal" from chat).
     */
    sessionId: String? = null,
) {
    // [小丘] 多标签：manager 为主路径（terminalSession 参数保留给旧调用方，
    // 传 null 时从 manager 池取活跃标签）。输出泵在 Manager 层常驻，
    // 切走标签/离开屏幕会话继续跑（v1 会话池语义）。
    val manager = remember { TerminalSessionManager.get() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val tab = remember { terminalSession?.let { null } ?: manager.open(sessionId, initCommand) }
        ?: manager.active ?: manager.createTab(sessionId)
    val terminalSession = tab.session
    val emulator = tab.emulator
    val inputController = rememberTerminalInputController()
    val scope = rememberCoroutineScope()
    var ctrlActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }
    var keysExpanded by remember { mutableStateOf(false) }

    // Wire emulator responses (DSR etc.) back to the PTY of the ACTIVE tab.
    DisposableEffect(terminalSession, emulator) {
        emulator.onResponse = { data -> terminalSession.sendRawBytes(data) }
        onDispose { emulator.onResponse = null }
    }

    // Clear emulator when session clearOutput() ticks.
    val clearVersion by terminalSession.clearVersion.collectAsStateEffect()
    LaunchedEffect(clearVersion) {
        if (clearVersion > 0) {
            emulator.feed("\u001Bc".toByteArray())   // RIS — full reset
        }
    }

    // T-android-terminal-keyboard-3a8f5e0b: Do NOT auto-focus the hidden input
    // EditText on open. Previously a 20-tick retry loop here force-popped the
    // IME on entry (matching iOS becomeFirstResponder), but on Android that
    // caused two issues: (1) keyboard pops up unsolicited when the user just
    // wants to read terminal output, (2) the back gesture's first press hides
    // the IME, then the still-living retry loop (or a focus-restore on next
    // composition) re-pops it, requiring a second back press to actually
    // leave the screen. Android convention for read-eval shells is to let the
    // user tap the canvas (handled below via `onTap = { requestFocus() }`) or
    // the keyboard-toggle button in the accessory bar to deliberately invoke
    // the IME — that single user action handles both opening the keyboard
    // and (via the toggle) closing it, and a single back press now exits.

    // [T-android-terminal-enter-keeps-focus] ...but DO auto-focus when a
    // hardware keyboard is attached.
    //
    // Every objection in the note above is about the soft IME: it pops up
    // unsolicited over the output, and it fights the back gesture. With a
    // physical keyboard there is no IME to pop — focus is invisible — so the
    // only thing auto-focus changes is that the keys the user presses arrive
    // somewhere. Without it, opening the terminal and typing does nothing at
    // all, with no on-screen hint as to why.
    val cfg = LocalConfiguration.current
    val hasHardwareKeyboard = cfg.keyboard == Configuration.KEYBOARD_QWERTY &&
        cfg.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
    LaunchedEffect(hasHardwareKeyboard) {
        if (hasHardwareKeyboard) inputController.requestFocus()
    }

    // [小丘] 离开终端页不停止会话（多标签后台跑）；仅清 URL broker 状态。
    DisposableEffect(Unit) {
        onDispose { MinisOpenUrlBroker.setTerminalVisible(false) }
    }

    // Claim the broker while the fullscreen terminal is up so ChatScreen
    // (still composed underneath this destination's stack) doesn't try to
    // present its own preview sheet on top — mirrors iOS ISHTerminalView.
    DisposableEffect(Unit) {
        MinisOpenUrlBroker.setTerminalVisible(true)
        onDispose { MinisOpenUrlBroker.setTerminalVisible(false) }
    }

    // OSC 1337 MinisOpenURL emitted by `/usr/local/bin/minis-open` is parsed
    // by TerminalEmulator and forwarded to MinisOpenUrlBroker. From the
    // standalone terminal we only route web schemes (http(s)/about) into an
    // in-app WebView preview; minis://-style chat resources need ChatScreen's
    // resolver and aren't reachable here, so we still consume them to avoid
    // leaking a stale pendingUrl back to chat on next attach.
    var previewUrl by remember { mutableStateOf<String?>(null) }
    val pendingUrl by MinisOpenUrlBroker.pendingUrl.collectAsStateEffect()
    LaunchedEffect(pendingUrl) {
        val uri = pendingUrl ?: return@LaunchedEffect
        if (MinisOpenUrlBroker.isWebScheme(uri.scheme)) {
            previewUrl = uri.toString()
        }
        MinisOpenUrlBroker.consume()
    }

    // T290: Layered layout — top bar fixed, canvas fills middle, accessory
    // bar pinned to bottom and lifted above the IME via Modifier.imePadding().
    //
    // Pre-T290 this was an off-window Popup + manually-tracked IME height
    // built around a Pixel/Gboard PAN-mode quirk. That misfired on pixel6
    // (bar still under the keyboard). With windowSoftInputMode=adjustResize
    // already set in the manifest, edge-to-edge enabled, and modern Pixel
    // builds reporting WindowInsets.ime correctly, a plain in-window Box +
    // imePadding does the right thing without any custom tracking.
    val accessoryBarHeightDp = if (keysExpanded) 134.dp else 74.dp

    Box(modifier = Modifier.fillMaxSize().background(TerminalBg)) {
        // Main content: top bar + canvas. imePadding() lifts the canvas
        // above the keyboard so it's never covered.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .imePadding()
                .padding(bottom = accessoryBarHeightDp),
        ) {
            // [小丘] 标签条即顶栏（v1 结构：标签管理是终端页第一公民，
            // 不再藏在标题栏下的暗区——用户"看不到"的根治）
            TerminalTabsBar(
                manager = manager,
                onClose = { terminalSession.stop(); onBack() },
                onClear = {
                    terminalSession.sendRawBytes(byteArrayOf(0x15))
                    terminalSession.clearOutput()
                    emulator.feed("\u001Bc".toByteArray())
                },
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // T194 part-2: native Android View backing gives us long-press
                // selection + ActionMode + ClipboardManager copy. The old
                // TerminalCanvasView is left in the package as a Compose-only
                // fallback if anything regresses with the View interop path.
                TerminalNativeViewCompose(
                    emulator = emulator,
                    onResize = { cols, rows ->
                        emulator.resize(cols, rows)
                        terminalSession.setWindowSize(cols, rows)
                    },
                    onTap = { inputController.requestFocus() },
                )
                TerminalInputView(
                    onInput = { bytes ->
                        // Any user input snaps back to live tail so typing is visible.
                        emulator.scrollOffset = 0
                        if (ctrlActive && bytes.size == 1) {
                            val ch = bytes[0].toInt().toChar().uppercaseChar()
                            if (ch in 'A'..'Z') {
                                terminalSession.sendRawBytes(byteArrayOf((ch - 'A' + 1).toByte()))
                                ctrlActive = false
                                return@TerminalInputView
                            }
                        }
                        terminalSession.sendRawBytes(bytes)
                    },
                    applicationCursorKeys = emulator.applicationCursorKeys,
                    controller = inputController,
                    modifier = Modifier.size(1.dp),
                )
            }
        }

        // T290: accessory bar — anchored to bottom of the parent Box and
        // lifted above the IME via imePadding(). When the keyboard is
        // closed, windowInsetsPadding(navigationBars) keeps it above the
        // gesture / nav bar. No Popup, no manual IME tracking.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .imePadding()
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            KeyboardAccessoryBar(
                ctrlActive = ctrlActive,
                altActive = altActive,
                keyboardVisible = inputController.isFocused,
                expanded = keysExpanded,
                onPaste = {
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    val text = cm?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                    if (text.isNotEmpty()) {
                        emulator.scrollOffset = 0
                        terminalSession.sendText(text)
                    }
                },
                onCtrlToggle = { ctrlActive = !ctrlActive },
                onAltToggle = { altActive = !altActive },
                onToggleKeyboard = {
                    if (inputController.isFocused) inputController.clearFocus()
                    else inputController.requestFocus()
                },
                onToggleExpanded = { keysExpanded = !keysExpanded },
                onSendRaw = { bytes ->
                    emulator.scrollOffset = 0
                    terminalSession.sendRawBytes(bytes)
                },
                onSendText = { text ->
                    emulator.scrollOffset = 0
                    // [小丘] 粘滞修饰键组合：CTRL+字母→控制码；ALT+字母→ESC前缀
                    val ch = text.singleOrNull()
                    val combo = when {
                        ctrlActive && ch != null && ch.isLetter() -> {
                            ctrlActive = false
                            byteArrayOf((ch.uppercaseChar().code - 'A'.code + 1).toByte())
                        }
                        altActive && ch != null && ch.isLetterOrDigit() -> {
                            altActive = false
                            byteArrayOf(0x1B) + text.toByteArray()
                        }
                        else -> null
                    }
                    if (combo != null) terminalSession.sendRawBytes(combo)
                    else terminalSession.sendText(text)
                },
            )
        }
        // [小丘] 悬浮 D-pad（v1：球态拖移/点开/位置持久化/出界自愈）
        FloatingDpad(
            onArrow = { dir ->
                emulator.scrollOffset = 0
                val prefix = if (emulator.applicationCursorKeys)
                    byteArrayOf(0x1B, 'O'.code.toByte())
                else byteArrayOf(0x1B, '['.code.toByte())
                terminalSession.sendRawBytes(prefix + byteArrayOf(dir.code.toByte()))
            },
        )

        previewUrl?.let { url ->
            com.xiaoqiu.ui.components.UrlPreviewSheet(
                url = url,
                onDismiss = { previewUrl = null },
            )
        }
    }
}

// ── Tiny helper: collectAsState for StateFlow<Int> without pulling all of androidx.lifecycle ──
@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateEffect(): androidx.compose.runtime.State<T> {
    val state = remember { androidx.compose.runtime.mutableStateOf(value) }
    LaunchedEffect(this) { collect { state.value = it } }
    return state
}

// ─── Top bar ──────────────────────────────────────────────────────────────────

@Composable
private fun TerminalTopBar(
    onClose: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TerminalBg)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularIconButton(
            icon = Icons.Default.Close,
            contentDescription = stringResource(R.string.common_close),
            tint = TerminalFg,
            onClick = onClose,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            stringResource(R.string.terminal_title),
            color = TerminalFg,
            style = TextStyle(
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 16.sp,
            ),
        )
        Spacer(modifier = Modifier.weight(1f))
        CircularIconButton(
            icon = Icons.Default.Brush,
            contentDescription = stringResource(R.string.terminal_clear),
            tint = TerminalGreen,
            onClick = onClear,
        )
    }
}

@Composable
private fun CircularIconButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(TopButtonBg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}

// ─── Keyboard accessory bar ───────────────────────────────────────────────────

@Composable
private fun KeyboardAccessoryBar(
    ctrlActive: Boolean,
    altActive: Boolean,
    keyboardVisible: Boolean,
    expanded: Boolean,
    onPaste: () -> Unit,
    onCtrlToggle: () -> Unit,
    onAltToggle: () -> Unit,
    onToggleKeyboard: () -> Unit,
    onToggleExpanded: () -> Unit,
    onSendRaw: (ByteArray) -> Unit,
    onSendText: (String) -> Unit,
) {
    // [小丘] v1 快捷键条 v7 完全体：常态两行编程高频 + 展开两行（符号+次级）。
    // 粘滞修饰键（CTRL/ALT）点亮待组合；控制键直接发控制码。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(AccessoryBg),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        // ── 行1：⌨ CTRL* ALT* | ESC TAB ⏎ ^C ⌫ ──
        KeyScrollRow {
            KeyCap("⌨", onClick = onToggleKeyboard, prominent = keyboardVisible)
            KeyCap("CTRL", sticky = ctrlActive, onClick = onCtrlToggle)
            KeyCap("ALT", sticky = altActive, onClick = onAltToggle)
            KeyCap("ESC") { onSendRaw(byteArrayOf(0x1B)) }
            KeyCap("TAB") { onSendRaw(byteArrayOf(0x09)) }
            KeyCap("⏎", prominent = true) { onSendRaw(byteArrayOf(0x0D)) }
            KeyCap("^C") { onSendRaw(byteArrayOf(0x03)) }
            KeyCap("⌫") { onSendRaw(byteArrayOf(0x7F)) }
            KeyCap("📋", prominent = true) { onPaste() }
        }
        if (expanded) {
            // ── 展开行A：编程符号横滚 ──
            KeyScrollRow {
                listOf("~", "|", "\\", "/", "<", ">", "[", "]", "{", "}", "(", ")",
                    "=", "+", "-", "_", "*", "&", "%", "$", "#", "@", "!", "\'", "\"",
                    ":", ";", ".", ",", "?", "^", "`").forEach { sym ->
                    KeyCap(sym) { onSendText(sym) }
                }
            }
            // ── 展开行B：Alt组合+历史控制+编辑键 ──
            KeyScrollRow {
                KeyCap("A·B") { onSendRaw(byteArrayOf(0x1B, 'b'.code.toByte())) }
                KeyCap("A·F") { onSendRaw(byteArrayOf(0x1B, 'f'.code.toByte())) }
                KeyCap("A·D") { onSendRaw(byteArrayOf(0x1B, 'd'.code.toByte())) }
                KeyCap("A·.") { onSendRaw(byteArrayOf(0x1B, '.'.code.toByte())) }
                KeyCap("^R") { onSendRaw(byteArrayOf(0x12)) }
                KeyCap("^K") { onSendRaw(byteArrayOf(0x0B)) }
                KeyCap("^Y") { onSendRaw(byteArrayOf(0x19)) }
                KeyCap("^P") { onSendRaw(byteArrayOf(0x10)) }
                KeyCap("^N") { onSendRaw(byteArrayOf(0x0E)) }
                KeyCap("INS") { onSendRaw(byteArrayOf(0x1B, '['.code.toByte(), '2'.code.toByte(), '~'.code.toByte())) }
                KeyCap("DEL") { onSendRaw(byteArrayOf(0x7F)) }
                KeyCap("SPC", prominent = true) { onSendRaw(byteArrayOf(0x20)) }
            }
        }
        // ── 行2：^D ^Z ^U ^W ^L | HOME END PGUP PGDN | ▾/▴ ──
        KeyScrollRow {
            KeyCap("^D") { onSendRaw(byteArrayOf(0x04)) }
            KeyCap("^Z") { onSendRaw(byteArrayOf(0x1A)) }
            KeyCap("^U") { onSendRaw(byteArrayOf(0x15)) }
            KeyCap("^W") { onSendRaw(byteArrayOf(0x17)) }
            KeyCap("^L") { onSendRaw(byteArrayOf(0x0C)) }
            KeyCap("HOME") { onSendRaw(byteArrayOf(0x1B, '['.code.toByte(), 'H'.code.toByte())) }
            KeyCap("END") { onSendRaw(byteArrayOf(0x1B, '['.code.toByte(), 'F'.code.toByte())) }
            KeyCap("PGUP") { onSendRaw(byteArrayOf(0x1B, '['.code.toByte(), '5'.code.toByte(), '~'.code.toByte())) }
            KeyCap("PGDN") { onSendRaw(byteArrayOf(0x1B, '['.code.toByte(), '6'.code.toByte(), '~'.code.toByte())) }
            KeyCap(if (expanded) "▴" else "▾", onClick = onToggleExpanded)
        }
    }
}

@Composable
private fun KeyScrollRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun KeyCap(
    label: String,
    sticky: Boolean = false,
    prominent: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    // [小丘] v1 .vk 样式：紧凑圆角键帽，最小宽度保证小标签可点，
    // 粘滞键点亮山绿、主键（⏎/^C/SPC）微亮底、常态深灰。
    val bg = when {
        sticky -> Color(0xFF3E7C59)
        prominent -> Color(0xFF2E4A38)
        else -> Color(0xFF3A3A40)
    }
    val fg = when {
        sticky -> Color.White
        prominent -> Color(0xFFE7F0EA)
        else -> Color(0xFF9CCFA8)
    }
    Box(
        modifier = Modifier
            .height(32.dp)
            .widthIn(min = 40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = { onClick?.invoke() })
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = fg,
            style = TextStyle(fontFamily = JetBrainsMonoFontFamily, fontSize = 12.sp),
            maxLines = 1,
        )
    }
}

