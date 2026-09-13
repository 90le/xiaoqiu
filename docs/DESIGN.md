# 小丘 v2 · 总体设计文档

> 版本：2.0.0-alpha
> 日期：2026-09-13
> 状态：初始化完成，待开发
> 前身：~/PiBridge（v1，已归档 tag v1-final）

---

## 一、产品定位

**小丘 = 住在用户手机里的随身 AI 工作台**

不是聊天 App，不是玩具——是让 AI 真正住进手机、操控手机、服务用户的**生产力工具**。

### 核心哲学（用户原话）

1. **AI 当 AI 用**：不写死模板，让 AI 自己判断说多少、怎么说、什么时候该干什么
2. **工程管稳定，AI 管智能**：管道我们做，内容层面交给模型
3. **用户哲学**：升级通用能力底座，不做场景堆砌
4. **手机就是 AI 的身体**：截屏、操作App、读通知、感知传感器

---

## 二、v1 教训（为什么重做）

| 教训 | 根因 | v2 对策 |
|---|---|---|
| 清数据后引擎丢失 | npm 生态打包不可控 | PRoot + Alpine rootfs 内置 APK（OpenMinis 方案）|
| 依赖地狱 | 手动选包永远漏 | OpenMinis 完整构建链（deps/ 从源码编译）|
| 前端配色反复修 | Vue 内联样式难维护 | Jetpack Compose + Material 3 主题系统 |
| 部署后白屏 | 缺 import/TDZ/时序竞态 | Kotlin 类型安全 + 编译期检查 |
| 修 A 坏 B | 20万行单文件+无测试 | OpenMinis 有完整测试体系 |

---

## 三、架构总览

### 3.1 技术栈

| 层 | 技术 | 说明 |
|---|---|---|
| 语言 | Kotlin | 全 Android 端 |
| UI | Jetpack Compose + Material 3 | 声明式 UI |
| 架构 | MVVM + Clean Architecture | |
| DI | Hilt | |
| 数据 | Room + DataStore + Protobuf | |
| 网络 | OkHttp + Retrofit | |
| 沙箱 | PRoot + Alpine Linux | AI 的"电脑" |
| 语音 | sherpa-onnx KWS + 声纹 | 流式唤醒 + 主人识别 |
| AI | 直连 OpenAI 兼容 API | Claude/GPT/Gemini/GLM |
| 终端 | 自建 Terminal Emulator | 参考OpenMinis terminal模块 |

### 3.2 模块图

```
┌─────────────────────────────────────────────────┐
│                   小丘 App                        │
├─────────┬─────────┬─────────┬─────────────────┤
│  UI 层   │  Agent  │  沙箱    │   设备集成        │
│  Compose│  Runtime│  PRoot   │   A11y/传感器    │
│         │         │  Alpine  │                 │
│  Onboard│  Chat   │  Node    │   悬浮球         │
│  Chat   │  Tools  │  Python  │   副屏           │
│  Term   │  Memory │  Shell   │   通知           │
│  Skills │  Soul   │          │   播报           │
│  Voice  │  Macros │          │   宏             │
│  Settings│ Plugins│          │                 │
└─────────┴─────────┴─────────┴─────────────────┘
```

### 3.3 从 v1 保留的能力

| 模块 | v1 实现 | 迁移方式 |
|---|---|---|
| KWS 流式唤醒 | sherpa-onnx zipformer | 直接复用模型+逻辑 |
| 声纹门禁 | speaker embedding + 余弦 | 直接复用 |
| 快脑/慢脑 | chat_fast 意图分流 | 重写为 Kotlin |
| 句级流式 TTS | 分段合成+PCM拼接 | 重写为 Kotlin |
| 97 个 MCP 工具 | Tools.java | 逐步迁移为 Kotlin |
| 悬浮球 | FloatBall.java | 迁移 |
| 隐形副屏 | VdManager.java | 迁移 |
| 通知播报 | NotifyListener.java | 迁移 |
| 记忆系统 | memory.json + 自动沉淀 | 重写为 Room |
| 宏系统 | macro_save/run | 重写为 Room |

### 3.4 从 OpenMinis 学习的设计

| 设计 | 位置 | 说明 |
|---|---|---|
| SoulStore | agent/SoulStore.kt | SOUL.md 人格文件（YAML+MD）|
| Onboarding | ui/onboarding/ | 5步引导流 |
| ToolLoopDetector | agent/ToolLoopDetector.kt | 滑动窗口防死循环 |
| ConfigConfirmationGate | config/confirm/ | 配置修改确认门控 |
| 语音纠错学习 | speech/correction/ | 混淆字典自学习 |
| PRoot 沙箱 | sandbox/ | Alpine Linux 完整环境 |
| OAuth 系统 | auth/ | Claude/Gemini/Kimi 设备流 |
| Browser 自动化 | browser/ | Agent 浏览网页 |
| 定时任务 | scheduled/ | AlarmReceiver 触发 |
| 插件市场 | plugins/ | 社区贡献安装 |

---

## 四、功能需求（FR）

### FR-1 语音对话（最高优先级）

| # | 需求 | 验收标准 |
|---|---|---|
| 1.1 | KWS 流式唤醒 | 喊"小丘" → 300ms 内响应 |
| 1.2 | 声纹门禁 | 他人/电视喊 → 不唤醒；主人 → 唤醒 |
| 1.3 | 连续对话 | 唤醒后多轮对话，6秒静默收尾 |
| 1.4 | 快脑直答 | 闲聊 → 快速模型直答（<3s）|
| 1.5 | 慢脑任务 | 复杂任务 → 强力模型执行 |
| 1.6 | 智能进度 | 执行中 → AI 自组织语言播报进度 |
| 1.7 | 句级流式 TTS | 长文 → 首句 3s 内出声 |
| 1.8 | 打断 | 播报中开口 → 停播续听 |
| 1.9 | 口语化总结 | 任务完成 → AI 自判详略汇报 |
| 1.10 | 全屏特效 | 唤醒时四色流光 + 状态药丸 |
| 1.11 | 停止按钮 | 一键停止（特效+播报+录音）|
| 1.12 | 语音纠错 | 用户修正 → 学习混淆字典 |

### FR-2 对话系统

| # | 需求 | 验收标准 |
|---|---|---|
| 2.1 | 多模型支持 | Claude/GPT/Gemini/GLM 可切换 |
| 2.2 | 流式输出 | 打字机效果 |
| 2.3 | 思考档位 | 7档，按模型能力灰显 |
| 2.4 | 工具调用 | AI 可调用设备工具 |
| 2.5 | 附件 | 图片/文件/拍照 |
| 2.6 | 编辑消息 | 退回重发 |
| 2.7 | 重试 | ↻ 重试上一条 |
| 2.8 | 快捷短语 | 常用指令一键发送 |
| 2.9 | 会话管理 | 历史/搜索/删除/工作区 |

### FR-3 终端

| # | 需求 | 验收标准 |
|---|---|---|
| 3.1 | 完整 Linux | Alpine 沙箱，可 apk add |
| 3.2 | 终端仿真 | xterm 兼容，颜色/转义 |
| 3.3 | 会话管理 | 多标签/重命名/重启 |
| 3.4 | 虚拟键盘 | 修饰键/组合键/方向键 |
| 3.5 | 选区复制 | 长按选词/拖选/复制 |
| 3.6 | 滚动+惯性 | 流畅滚动/回底按钮 |
| 3.7 | AI 命令区 | AI 跑的命令可见+可交互 |

### FR-4 设备集成

| # | 需求 | 验收标准 |
|---|---|---|
| 4.1 | 无障碍操控 | 读屏幕/点按/滑动/输入 |
| 4.2 | 隐形副屏 | 后台操控不干扰用户 |
| 4.3 | 通知播报 | 微信/短信/物流语音播报 |
| 4.4 | 黑白名单 | 双模式来源管理 |
| 4.5 | 悬浮球 | 单击/双击/拖动 |
| 4.6 | 系统控制 | WiFi/蓝牙/亮度/音量 |

### FR-5 记忆系统

| # | 需求 | 验收标准 |
|---|---|---|
| 5.1 | 自动沉淀 | 对话后 AI 提取值得记住的事 |
| 5.2 | 核心注入 | pin 条目自动注入上下文 |
| 5.3 | 透明时间线 | 可见"AI 刚记住了什么" |
| 5.4 | 分类管理 | user/app/voice/fact/person/skill |
| 5.5 | 批量清理 | 按分类/时间清理 |

### FR-6 宏系统

| # | 需求 | 验收标准 |
|---|---|---|
| 6.1 | 会话提取 | 从最近操作流程自动生成宏 |
| 6.2 | 参数化 | p1-p3 占位符 |
| 6.3 | 进度播报 | 执行中语音汇报 |
| 6.4 | 导入导出 | 跨设备移植 |

### FR-7 工具面板

| # | 需求 | 验收标准 |
|---|---|---|
| 7.1 | Schema 表单 | 参数自动生成表单 |
| 7.2 | 语义分组 | 按能力域分组 |
| 7.3 | 收藏/最近 | 常用一键直达 |
| 7.4 | 结果智能渲染 | 截图/列表/JSON/状态 |

### FR-8 人格系统（SoulStore）

| # | 需求 | 验收标准 |
|---|---|---|
| 8.1 | SOUL.md 编辑 | 用户自定义 AI 名字/emoji/风格 |
| 8.2 | 身份注入 | SOUL.md 内容注入系统提示 |
| 8.3 | 多人格 | 可切换不同人格 |

### FR-9 定时任务

| # | 需求 | 验收标准 |
|---|---|---|
| 9.1 | 定时触发 | 闹钟时间到 → 执行 AI 任务 |
| 9.2 | 周期任务 | 每天/每周/自定义 |
| 9.3 | 结果通知 | 执行完成语音播报 |

### FR-10 安全

| # | 需求 | 验收标准 |
|---|---|---|
| 10.1 | 操作确认门控 | 危险操作须用户确认 |
| 10.2 | 工具死循环检测 | 重复调用自动检测 |
| 10.3 | 权限引导 | 高级权限白话说明+一键直达 |

---

## 五、非功能需求（NFR）

| # | 需求 | 指标 |
|---|---|---|
| N-1 | 冷启动 | <2s（不含沙箱）|
| N-2 | 唤醒延迟 | <500ms |
| N-3 | APK 大小 | <80MB（含沙箱）|
| N-4 | 清数据恢复 | 自动，<30s，无需网络 |
| N-5 | 电池 | 待机 <2%/小时 |
| N-6 | 崩溃率 | <0.1% |
| N-7 | 兼容 | Android 8.0+（API 26+）|

---

## 六、UI 设计规范

### 6.1 主题：山野风

```kotlin
// Material 3 Color Scheme
val XiaoQiuColors = lightColorScheme(
    primary = Color(0xFF3E7C59),      // 山绿
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7F0EA), // 浅山绿
    secondary = Color(0xFFE8853D),    // 晨橙
    background = Color(0xFFF7F3EC),   // 米白
    surface = Color.White,
    onSurface = Color(0xFF22301F),   // 墨绿
    outline = Color(0xFFE8E1D4),     // 米线
    error = Color(0xFFC24B3C),       // 错误红
)
```

### 6.2 页面结构

```
底部导航（5 Tab）
├── 🏠 首页（总览+快捷+语音）
├── 💬 对话（聊天+终端 Tab）
├── ⚡ 自动化（宏+定时）
├── 🧩 工具（能力面板）
└── ⚙️ 设置（全部配置）
```

### 6.3 Onboarding 流程

```
Step 1: 欢迎页（Logo + 四大能力）
Step 2: 模型选择（API Key 输入）
Step 3: 权限引导（白话+一键直达）
Step 4: 人格设置（名字/emoji/风格）
Step 5: 试一试（语音/打字/跑命令）
```

---

## 七、数据架构

### 7.1 Room 数据库

```kotlin
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String,
    val content: String, // JSON blocks
    val timestamp: Long,
    val model: String?,
    val thinkingLevel: String?,
)

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey val key: String,
    val value: String,
    val category: String, // user/app/voice/fact/person/skill
    val pinned: Boolean,
    val source: String, // auto/voice/app/manual
    val createdAt: Long,
)

@Entity(tableName = "macros")
data class MacroEntity(
    @PrimaryKey val name: String,
    val description: String,
    val steps: String, // JSON
    val createdAt: Long,
)

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val lastMessage: String,
    val createdAt: Long,
    val updatedAt: Long,
)
```

### 7.2 DataStore（配置）

```kotlin
object SettingsKeys {
    val FAST_MODEL = stringPreferencesKey("fast_model")
    val FAST_THINKING = stringPreferencesKey("fast_thinking")
    val TTS_ENGINE = stringPreferencesKey("tts_engine")
    val TTS_VOICE = stringPreferencesKey("tts_voice")
    val WAKE_ENABLED = booleanPreferencesKey("wake_enabled")
    val VOICEPRINT_THRESHOLD = floatPreferencesKey("voiceprint_threshold")
    val NOTIFICATION_MODE = stringPreferencesKey("notification_mode")
    // ...
}
```

### 7.3 文件存储

```
/data/data/com.xiaoqiu/files/
├── soul/SOUL.md          // 人格文件
├── memory/               // 记忆日志
├── macros/               // 宏定义
├── skills/               // 技能目录
├── plugins/              // 插件
├── rootfs/               // Alpine 沙箱
└── cache/                // 语音/截图缓存
```

---

## 八、迁移计划

### Phase 0：环境搭建（当前）
- [x] 复制 OpenMinis → xiaoqiu
- [x] 改包名 com.xiaoqiu
- [x] 改应用名 小丘
- [x] 清理旧引用
- [ ] 配置 Android SDK 构建环境
- [ ] 第一次编译通过

### Phase 1：核心可用（2周）
- [ ] 删 iOS 代码（减体积）
- [ ] 接入 GLM API（智谱）
- [ ] 基础对话（发送/接收/流式）
- [ ] 沙箱可用（PRoot 启动）
- [ ] 终端可用（基础功能）

### Phase 2：语音体系（2周）
- [ ] KWS 流式唤醒（sherpa-onnx）
- [ ] 声纹门禁（speaker embedding）
- [ ] 快脑/慢脑分流
- [ ] 句级流式 TTS
- [ ] 连续对话循环
- [ ] 全屏特效

### Phase 3：工具+设备（2周）
- [ ] 迁移 97 个 MCP 工具
- [ ] 无障碍操控（A11y Service）
- [ ] 隐形副屏（VirtualDisplay）
- [ ] 通知播报系统
- [ ] 悬浮球

### Phase 4：智能系统（2周）
- [ ] 记忆系统（自动沉淀+核心注入）
- [ ] 宏系统（会话提取+参数化）
- [ ] SoulStore 人格
- [ ] 定时任务
- [ ] 工具死循环检测

### Phase 5：UI 打磨（1周）
- [ ] 山野风主题
- [ ] Onboarding 5步
- [ ] 全部中文化
- [ ] 快捷短语
- [ ] 高级权限引导

### Phase 6：测试+发布（1周）
- [ ] 全功能回归测试
- [ ] 性能优化
- [ ] GitHub Actions CI/CD
- [ ] 发布 v2.0.0

---

## 九、风险与对策

| 风险 | 概率 | 对策 |
|---|---|---|
| PRoot 沙箱在部分设备不可用 | 中 | 降级模式（无沙箱，仅 API）|
| sherpa-onnx 兼容性 | 低 | v1 已验证可用 |
| 编译环境复杂 | 高 | GitHub Actions 云编译 |
| API 费用 | 低 | 支持用户自带 Key |
| MIUI 后台限制 | 高 | 文档引导+前台服务 |

---

## 十、术语表

| 术语 | 含义 |
|---|---|
| 快脑 | 意图识别/口语化/进度播报用的轻量模型 |
| 慢脑 | 任务执行用的强力模型 |
| KWS | Keyword Spotting，关键词检测 |
| 声纹 | Speaker Embedding，说话人身份验证 |
| PRoot | 用户空间根文件系统（无 root 的沙箱方案）|
| 沙箱 | AI 的"电脑"（Alpine Linux 环境）|
| 灵魂 | SOUL.md，AI 的人格定义文件 |
| 混淆字典 | 语音识别纠错学习的数据 |
| 工作区 | 独立的工作上下文（文件/对话隔离）|

---

> 本文档随开发持续更新。最后更新：2026-09-13
