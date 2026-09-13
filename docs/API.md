# 小丘 v2 · API 规范

> 引擎通信协议：直连 OpenAI 兼容 API

---

## 一、支持的提供商

| 提供商 | Base URL | 模型 | 认证 |
|---|---|---|---|
| 智谱 GLM | https://open.bigmodel.cn/api/paas/v4 | glm-5.3, glm-5.3-flash, glm-4.7 | Bearer API Key |
| Anthropic | https://api.anthropic.com/v1 | claude-* | x-api-key + OAuth |
| OpenAI | https://api.openai.com/v1 | gpt-*, o-* | Bearer API Key |
| Google | https://generativelanguage.googleapis.com/v1beta | gemini-* | API Key |
| 自定义 | 用户填写 | 任意 | 用户填写 |

---

## 二、消息格式（内部统一）

```kotlin
data class ChatMessage(
    val id: String,
    val role: Role,          // system / user / assistant / tool
    val content: List<ContentBlock>,
    val timestamp: Long,
    val model: String? = null,
    val thinkingLevel: String? = null,
)

sealed class ContentBlock {
    data class Text(val text: String) : ContentBlock()
    data class Thinking(val text: String) : ContentBlock()
    data class ToolCall(
        val id: String,
        val name: String,
        val arguments: String, // JSON
    ) : ContentBlock()
    data class ToolResult(
        val callId: String,
        val content: String,
        val isError: Boolean = false,
    ) : ContentBlock()
    data class Image(val dataUrl: String) : ContentBlock()
}
```

---

## 三、Agent 循环

```
用户消息 → [快脑分流] → 
  ├─ 闲聊 → 快速模型 → 直答
  └─ 任务 → 强力模型 → 
      ├─ 文本输出（流式）
      ├─ 工具调用 → 执行 → 结果 → 继续循环
      └─ 最终回复 → 口语化 → TTS
```

### 快脑提示词（意图分流）

```
你是小丘的意图识别模块。分析用户输入，返回 JSON：
A. 闲聊/常识 → {"type":"chat","answer":"<口语回答>"}
B. 需要执行 → {"type":"task","reply":"<口语确认>","prompt":"<优化后指令>"}
```

### 慢脑系统提示

```
[SOUL.md 内容]
[核心记忆]
[工具清单]
[操作决策循环]
[行为准则]
```

---

## 四、工具调用协议

```kotlin
interface AgentTool {
    val name: String
    val description: String
    val parameters: JsonObject // JSON Schema
    suspend fun execute(args: JsonObject): ToolResult
}

data class ToolResult(
    val content: String,
    val isError: Boolean = false,
    val data: Any? = null,
)
```

### 工具分类

| 类别 | 前缀 | 示例 |
|---|---|---|
| 无障碍 | ui_ | ui_tap, ui_swipe, ui_read |
| 视觉 | vision_ | vision_ask, vision_tap |
| 系统 | sys_ | sys_wifi, sys_bluetooth |
| 通知 | notify_ | notify_read, notify_post |
| 应用 | app_ | app_launch, app_list |
| 文件 | file_ | file_read, file_write |
| 记忆 | mem_ | mem_save, mem_search |
| 宏 | macro_ | macro_run, macro_save |
| 语音 | voice_ | voice_speak, voice_record |
| 终端 | term_ | term_run, term_input |

---

## 五、语音管线协议

### KWS（关键词检测）

```
AudioRecord(16kHz mono) → [sherpa-onnx KWS] → 命中"小丘"
  → [声纹验证] → 通过 → 进入会话
  →           → 失败 → 忽略
```

### TTS（语音合成）

```
文本 → [口语化改写（可选）] → 
  ├─ ≤400字 → 整段合成 → 播放
  └─ >400字 → 句级分段 → 并行合成 → 顺序播放
```

### 进度播报

```
工具调用事件 → [AI 生成自然语言] → TTS 播报
  节流：15s 间隔
  数据：当前工具名/步数/产字数
```

---

## 六、记忆协议

### 自动沉淀

```
对话完成 → [快脑提取] → 
  ├─ 有值得记住的 → memory_save(cat, key, value, src=auto)
  └─ 无 → 跳过
```

### 核心注入

```
每次对话前 → 查询 pinned 记忆 → 注入系统提示
  格式：[小丘对用户的核心认知]
        - 偏好1
        - 偏好2
```

---

## 七、配置模型

### DataStore 键

| 键 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| fast_model | String | glm-5.3-flash | 快脑模型 |
| fast_thinking | String | off | 快脑思考档 |
| fast_max_tokens | Int | 32768 | 快脑输出上限 |
| task_model | String | glm-5.3 | 慢脑模型 |
| task_thinking | String | low | 慢脑思考档 |
| tts_engine | String | auto | TTS 引擎 |
| tts_voice | String | tongtong | TTS 音色 |
| wake_enabled | Boolean | false | 语音唤醒 |
| voiceprint_threshold | Float | 0.60 | 声纹阈值 |
| notification_mode | String | whitelist | 播报模式 |
| notification_whitelist | String | com.tencent.mm | 白名单 |
| notification_blacklist | String | | 黑名单 |
| notification_silent | String | | 静默时段 |
| memory_auto | Boolean | true | 自动沉淀 |
| memory_inject | Boolean | true | 核心注入 |
