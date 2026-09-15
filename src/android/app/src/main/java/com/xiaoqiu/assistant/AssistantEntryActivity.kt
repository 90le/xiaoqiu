package com.xiaoqiu.assistant

/**
 * [小丘] 系统语音助手入口（战役 F 地基）。
 *
 * 声明 android.intent.action.ASSIST 后，本应用出现在
 * 设置 → 默认应用 → 语音助手 / 设备助手 候选列表（与 ChatGPT、
 * 超级小爱、Google 并列）。设为默认后，长按电源键 / 系统助手手势
 * 唤起本入口 → 透明跳转 `xiaoqiu://action/voice_chat` 新建对话并
 * 自动弹出语音面板。
 */
class AssistantEntryActivity : android.app.Activity() {

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("xiaoqiu://action/voice_chat"),
                ),
            )
        }
        finish()
    }
}
