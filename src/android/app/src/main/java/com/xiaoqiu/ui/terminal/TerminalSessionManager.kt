package com.xiaoqiu.ui.terminal

import android.content.Context
import com.xiaoqiu.sandbox.TerminalSession
import com.xiaoqiu.ui.terminal.emulator.TerminalEmulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * [小丘] 终端多标签会话池 —— v1 termStore.js 的 Kotlin 对应物。
 *
 * v1 设计（Terminal.vue 完全体）：
 *  - 会话池：sessions{} + order[]；标签横滚；状态点（活=亮/死=灰）；长按菜单
 *    （重命名/重启/关闭）；＋新建；切走后台不杀，回来内容还在。
 *
 * 关键点：输出泵在 Manager 层常驻（不随屏幕生命周期），切走标签输出继续进
 * emulator 缓冲，切回内容完整；离开终端页会话继续跑（v1 语义）。
 */
class TerminalSessionManager(private val appContext: Context) {

    class Tab(
        val id: String,
        val session: TerminalSession,
        val emulator: TerminalEmulator,
        initialTitle: String,
    ) {
        var title: String by androidx.compose.runtime.mutableStateOf(initialTitle)
            private set

        fun rename(value: String) {
            title = value.trim().ifEmpty { initialTitle }
        }
    }

    val tabs = androidx.compose.runtime.mutableStateListOf<Tab>()
    var activeId: String? by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set

    val active: Tab?
        get() = tabs.firstOrNull { it.id == activeId } ?: tabs.firstOrNull()

    private var counter = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pumpJobs = mutableMapOf<String, Job>()

    /**
     * 打开终端入口：带 initCommand（从聊天/深链发起）→ 永远新开标签；
     * 纯终端按钮 → 激活现有（无则新建）。镜像 v1 openTerminal 语义。
     */
    fun open(sessionId: String? = null, initCommand: String? = null): Tab {
        if (initCommand.isNullOrBlank() && sessionId == null) {
            (active ?: createTab()).let { activate(it.id); return it }
        }
        return createTab(sessionId = sessionId, initCommand = initCommand)
    }

    fun createTab(sessionId: String? = null, initCommand: String? = null): Tab {
        counter += 1
        val session = TerminalSession(appContext)
        val emulator = TerminalEmulator()
        val tab = Tab(
            id = UUID.randomUUID().toString().take(8),
            session = session,
            emulator = emulator,
            initialTitle = "t$counter",
        )
        tabs.add(tab)
        activate(tab.id)

        // 输出泵：常驻收集，后台标签也持续喂 emulator（PTY 缓冲不积压）。
        pumpJobs[tab.id] = scope.launch {
            session.outputBytes.collect { bytes -> emulator.feed(bytes) }
        }
        session.start(sessionId = sessionId)
        if (!initCommand.isNullOrBlank()) {
            scope.launch {
                delay(500)
                session.sendText(initCommand)
            }
        }
        return tab
    }

    fun activate(id: String) { activeId = id }

    fun rename(id: String, title: String) {
        tabs.firstOrNull { it.id == id }?.rename(title)
    }

    /** 关闭标签：停会话 + 停泵 + 移除；相邻标签接替 active。 */
    fun killTab(id: String) {
        val idx = tabs.indexOfFirst { it.id == id }
        if (idx < 0) return
        val tab = tabs.removeAt(idx)
        pumpJobs.remove(id)?.cancel()
        tab.session.stop()
        if (activeId == id) {
            activeId = tabs.getOrNull(idx.coerceAtMost(tabs.lastIndex))?.id
                ?: tabs.firstOrNull()?.id
        }
    }

    /** 重启：清屏重来（同一标签，emulator 复位，会话重 boot）。 */
    fun restartTab(id: String) {
        val tab = tabs.firstOrNull { it.id == id } ?: return
        tab.session.stop()
        tab.emulator.feed("\u001Bc".toByteArray()) // RIS 全清
        tab.session.start()
    }

    /** 关闭全部（App 退出兜底；平时离开屏幕不调用）。 */
    fun shutdown() {
        tabs.forEach { it.session.stop() }
        pumpJobs.values.forEach { it.cancel() }
        pumpJobs.clear()
        tabs.clear()
        activeId = null
    }

    fun isAlive(tab: Tab): Boolean =
        tab.session.state.value.let {
            it == TerminalSession.State.RUNNING || it == TerminalSession.State.BOOTING
        }

    companion object {
        @Volatile private var instance: TerminalSessionManager? = null
        fun init(context: Context): TerminalSessionManager =
            instance ?: synchronized(this) {
                instance ?: TerminalSessionManager(context.applicationContext)
                    .also { instance = it }
            }
        fun get(): TerminalSessionManager = requireNotNull(instance) {
            "TerminalSessionManager 未初始化：MinisApp.onCreate 应先调用 init()"
        }
    }
}
