# 小丘 v2 · 架构细节（OpenMinis 底子勘察）

> 生成：2026-09-13 · 首编译已通过（CI 8m44s 全绿）· App 已装手机可运行

---

## 一、代码全景

**35 个模块 / 617 个 Kotlin 文件 / 20.8 万行**（src/android，不含 iOS 442 个 Swift）

```
com.xiaoqiu/
├── 【UI 层】165 文件
│   ├── ui/chat/        对话（20+ 文件，ChatViewModel 12383 行是全 App 心脏）
│   ├── ui/sandbox/     文件浏览器 + rootfs 管理
│   ├── ui/settings/    设置（含 backup/skills/storage 等子页）
│   ├── ui/onboarding/  引导流（已有！）
│   ├── ui/terminal/    终端 UI
│   ├── ui/theme/       主题（Theme.kt + ChatColors.kt，小丘化入口）
│   ├── ui/browser/     Agent 浏览器
│   └── ui/sessions/    会话列表
│
├── 【数据层】59 文件
│   ├── data/db/        Room（ChatDao/ProviderConfig/AppDatabase）
│   └── data/model/     AgentContentPart/ToolDefinition 等领域模型
│
├── 【模型接入层】provider/ 29 文件
│   ├── anthropic/  gemini/  openai/  openrouter/  antigravity/
│   ├── ProviderFactory.kt        ← 工厂：按配置实例化
│   ├── ThinkingLevelCatalog.kt   ← 思考档目录（v1 的 7 档对应物）
│   ├── ModelsDevApi.kt           ← models.dev 模型索引
│   └── ProviderModelsCache.kt    ← 模型列表缓存
│   ⚠️ 无智谱 GLM 直连 → 走 openai 兼容（自定义 baseUrl）
│
├── 【沙箱层】sandbox/ 37 文件
│   ├── PRootKernel.kt       ← PRoot 内核接口
│   ├── RootfsManager.kt     ← Alpine rootfs 下载/安装/恢复（660行）
│   ├── TerminalSession.kt   ← 终端会话
│   ├── PersistentShell.kt   ← 持久 shell（Agent 用的长连接）
│   ├── ShellExecutor.kt     ← 命令执行器
│   ├── ExecutionCoordinator.kt ← 执行协调
│   └── NativeOffload.kt     ← native 卸载服务器（App 启动时绑 socket）
│
├── 【语音层】speech/ 27 文件
│   ├── SpeechRecognitionManager.kt（引擎管理）
│   ├── ProviderSpeechRecognitionEngine / SystemSpeechRecognitionEngine
│   ├── TextToSpeechManager.kt（TTS）
│   ├── VoiceActivityDetector.kt（VAD——Silero+ONNX）
│   ├── ReadAloudPlayer.kt（朗读播放器）
│   └── correction/（语音纠错学习——v1 想要的！）
│   ⚠️ 无 KWS 关键词唤醒 → v1 的 sherpa KWS 迁移挂载点
│
├── 【工具层】tools/ + agent/
│   ├── tools/AgentTools.kt（仅 4 个：shell_execute/browser_use/memory_write/memory_get）
│   ├── tools/（FileEdit/FileRead/FileWrite/ReadImage/BrowserUse/MemoryTools）
│   ├── agent/SoulStore.kt（人格系统 SOUL.md）
│   ├── agent/ToolLoopDetector.kt（418行 死循环检测）
│   ├── agent/InterruptedTailDetector.kt
│   └── agent/shell/
│   ⚠️ v1 的 97 个工具 → AgentTools.kt 扩展挂载点
│
├── 【无障碍】accessibility/ 4 文件
│   ├── MinisAccessibilityService.kt（读屏/节点树）
│   └── AccessibilityRecoveryManager / NodeRegistry / RestrictedSettingsManager
│   ⚠️ 基础能力有；v1 的操控工具（tap/swipe/setText）→ tools/ 层补
│
├── 【服务】service/ 8 文件
│   ├── AgentForegroundService.kt（前台服务：后台执行 Agent）
│   ├── SessionConcurrencyManager.kt
│   ├── ToolOverlayController.kt（工具执行浮层）
│   └── DynamicIslandSupport.kt
│
├── 【其他能力】
│   ├── auth/（OAuth 设备流：Claude/Gemini/Kimi）11 文件
│   ├── backup/（rclone 备份：SMB/WebDAV/SFTP）16 文件
│   ├── scheduled/（定时任务 ScheduledAgentRunner）5 文件
│   ├── browser/（Agent 网页自动化）9 文件
│   ├── notification/ 2 文件（基础；v1 播报系统挂载点）
│   ├── config/（ConfigConfirmationGate 配置确认门控）20 文件
│   ├── i18n/（国际化——中文化入口）
│   ├── deeplink/ share/ webapp/ debug/ diagnostics/ crash/ power/ logging/ …
│   └── mcp/oauth（MCP OAuth 支持）
```

---

## 二、核心链路：一次对话怎么跑

```
用户输入
  → ChatViewModel.sendMessage()
  → [历史压缩检查]（上下文超限 → 原地压缩 → 重跑迭代）
  → ProviderFactory 创建 LLMProvider（按会话模型配置）
  → 流式请求（OkHttp SSE）
  → 内容块流回：Text（打字机）/ Thinking（思考展示）/ ToolUse
  → ToolUse 出现：
      → ToolLoopDetector 检查（滑动窗口 12 次重复 → 中断）
      → 工具执行（tools/ 层，沙箱或系统调用）
      → ToolResult 追加进消息历史
      → 下一轮迭代（硬上限：单轮最大迭代数，ChatViewModel:403）
  → 无 ToolUse → 回答完成
  → [SoulStore 人格] 贯穿系统提示
  → [记忆工具] Agent 自主 memory_write/get
```

关键设计（v1 没有的）：
- **历史原地压缩**：超限不丢上下文，压缩后重跑同轮
- **迭代硬上限**：防工具风暴
- **ToolLoopDetector**：重复模式检测
- **前台服务**：后台继续跑 Agent（MIUI 杀不掉）
- **SessionConcurrencyManager**：多会话并发管理

---

## 三、v1 资产迁移挂载点（差距分析）

| v1 资产 | v2 现状 | 挂载点 | 优先级 |
|---|---|---|---|
| KWS 流式唤醒（sherpa） | ❌ 无 | speech/ 新增 WakeWordEngine | P0 |
| 声纹门禁 | ❌ 无 | speech/ 与 KWS 合并实现 | P0 |
| 快脑/慢脑分流 | ❌ 无（单循环） | ChatViewModel 入口前加 FastRouter | P1 |
| 句级流式 TTS | ⚠️ 有 TTS 无分段流式 | TextToSpeechManager 增强 | P1 |
| 97 个 MCP 工具 | ⚠️ 仅 4 个 | tools/AgentTools.kt 大扩展 | P0 |
| 无障碍操控（tap/swipe） | ⚠️ 有读屏无操控 | accessibility/ + tools/ | P0 |
| 通知播报系统 | ⚠️ notification/ 骨架 | notification/ 重写 | P1 |
| 悬浮球 | ❌ 无 | 新模块 ui/overlay/ | P2 |
| 隐形副屏 | ❌ 无 | 新模块（VdManager 移植） | P2 |
| 宏系统 | ❌ 无 | data/ + ui/macros/ | P2 |
| 智谱 GLM 直连 | ❌ 无 | provider/openai/ 自定义 baseUrl | P0 |
| 山野风主题 | ❌ OpenMinis 默认 | ui/theme/Theme.kt | P1 |
| 中文界面 | ⚠️ 英文 | i18n/ | P0 |

**Phase 1 重点 = P0 全清**：GLM 接入 → 工具扩展 → 唤醒系统 → 中文

---

## 四、构建流水线（已跑通）

```
GitHub Actions（ubuntu-latest，8m44s）
├── checkout + submodules（deps/proot）
├── JDK17 + SDK36 + NDK27 + CMake3.22
├── Go → gomobile → rclone.aar → app/libs/
├── build_proot.sh → assets/proot-aarch64 + jniLibs/libproot*.so
├── gradlew assembleDebug
└── APK 57MB → artifact → adbc install

产物：com.xiaoqiu v1.13（已装机运行 ✅）
```

---

## 五、值得抄的 OpenMinis 细节（v2 直接继承）

1. **SoulStore**：SOUL.md 人格文件，用户可编辑 AI 名字/性格
2. **语音纠错学习**（speech/correction/）：从用户修正中自建混淆字典
3. **ConfigConfirmationGate**：危险配置改动要确认
4. **NativeOffload abstract socket**：native 卸载通道（App 启动即绑）
5. **DynamicIslandSupport**：灵动岛式状态展示
6. **ToolOverlayController**：工具执行时全局浮层
7. **RootfsManager**：rootfs 版本化+损坏自愈（v1 离线恢复的正确做法）
8. **bashism 规则表**：shared/ 单一真相源，构建时拷 assets
9. **InterruptedTailDetector**：流式中断的残留检测
10. **模型索引**：models.dev API + 本地缓存 + 思考档目录
