# 小丘 v2 · 进度跟踪

> 最后更新：2026-09-13
> 当前阶段：Phase 1（核心可用）

---

## 总体进度

| 阶段 | 状态 | 开始 | 完成 | 进度 |
|---|---|---|---|---|
| Phase 0 环境搭建 | ✅ 完成 | 09-13 | 09-13 | 100% |
| Phase 1 核心可用 | ⬜ | — | — | 0% |
| Phase 2 语音体系 | ⬜ | — | — | 0% |
| Phase 3 工具+设备 | ⬜ | — | — | 0% |
| Phase 4 智能系统 | ⬜ | — | — | 0% |
| Phase 5 UI 打磨 | ⬜ | — | — | 0% |
| Phase 6 测试+发布 | ⬜ | — | — | 0% |

---

## Phase 0 详细任务

| # | 任务 | 状态 | 备注 |
|---|---|---|---|
| 0.1 | 复制 OpenMinis → xiaoqiu | ✅ | 完成 |
| 0.2 | 改包名 com.xiaoqiu | ✅ | 全量替换 |
| 0.3 | 改应用名 小丘 | ✅ | strings.xml |
| 0.4 | 清理旧引用 | ✅ | 0 残留 |
| 0.5 | Git 初始化 | ✅ | main 分支 |
| 0.6 | 设计文档 | ✅ | docs/DESIGN.md |
| 0.7 | 进度文档 | ✅ | docs/PROGRESS.md |
| 0.8 | 编译环境 | ✅ | **GitHub Actions 云端**（Termux 无 NDK） |
| 0.9 | 第一次编译通过 | ✅ | CI 第4跑成功 8m47s，58MB APK 已装机 |
| 0.10 | 推送 GitHub | ✅ | 90le/xiaoqiu |

### 编译环境决策

- **本地 Termux 不可行**：NDK 官方只发 x86_64 Linux 二进制，手机 aarch64 跑不了；OpenMinis 有 cpp 三件套（pty_bridge/jieba_jni/crash_handler）必须 NDK+CMake
- **GitHub Actions 方案**：ubuntu-latest + JDK17 + SDK36 + NDK 27 + Go（rclone.aar）+ submodule（proot 源码）→ assembleDebug → artifact APK

### 编译流水线（CI 步骤）

```
checkout(submodules) → JDK17 → SDK36/NDK27/CMake → Go
→ build_rclone_android.sh（gomobile 编 rclone.aar → app/libs/）
→ build_proot.sh（NDK 编 proot-aarch64 + libproot.so）
→ provider-customization.properties 模板
→ gradlew assembleDebug → APK artifact
```

---

## v1 归档信息

| 项 | 值 |
|---|---|
| 项目路径 | ~/PiBridge |
| Git tag | v1-final |
| 最终提交 | 5ad85de |
| 代码量 | 前端 40 模块 ~750KB + Java ~50 文件 |
| APK | 62MB |
| 功能 | 语音+终端+六大页面+记忆+宏+播报 |
| 遗留问题 | 清数据后引擎无法恢复（npm 打包不可控）|

---

## 从 v1 迁移的资产清单

### 模型文件（直接复用）
| 文件 | 大小 | 来源 |
|---|---|---|
| sherpa-kws zipformer int8 | 5MB | APK assets |
| speaker embedding (eres2net) | 39.6MB | APK assets |
| 预生成回应音频 | ~1MB | wake-sounds/ |

### 核心算法（需重写为 Kotlin）
| 功能 | v1 文件 | 行数 | 复杂度 |
|---|---|---|---|
| KWS 流式检测 | WakeService.java | ~700 | 高 |
| 声纹门禁 | Tools.java (spkEmbed) | ~100 | 中 |
| 快脑意图分流 | Tools.java (chat_fast) | ~200 | 中 |
| 句级流式 TTS | Tools.java (speakCloudStream) | ~150 | 中 |
| 同音归一化 | WakeService.java (wakeNorm) | ~20 | 低 |
| 回声免疫 | WakeService.java (markSpoken/isEcho) | ~30 | 低 |
| 记忆自动沉淀 | Tools.java (memory_extract) | ~50 | 低 |
| 口语化改写 | Tools.java (voiceFriendly) | ~40 | 低 |

### MCP 工具（逐步迁移）
| 类别 | 数量 | 优先级 |
|---|---|---|
| 无障碍操控 | 15 | 🔴 高 |
| 视觉/截图 | 10 | 🔴 高 |
| 系统控制 | 12 | 🟡 中 |
| 通知/消息 | 8 | 🟡 中 |
| 应用管理 | 8 | 🟡 中 |
| 语音/TTS | 7 | 🟡 中 |
| 记忆/宏 | 10 | 🟢 低 |
| 文件操作 | 5 | 🟢 低 |
| 其他 | 22 | 🟢 低 |

### UI 设计（重新实现）
| 页面 | v1 特点 | v2 对应 |
|---|---|---|
| 首页 | 问候+状态chips+语音按钮+快捷 | Dashboard |
| 对话 | Vue+自定义气泡+工具条 | ChatScreen |
| 终端 | xterm.js+虚拟键盘 | TerminalScreen |
| 设置 | 8分组子页+底部弹层选择器 | SettingsScreen |
| 播报 | 双模式黑白名单+应用选择器 | NotificationSettings |
| 记忆 | 分类+时间线+核心区 | MemoryScreen |
| 工具 | schema表单+8域分组 | ToolsScreen |
| 自动化 | 提取飞轮+编辑器+步骤预览 | MacrosScreen |
| 设备 | 状态三卡+六开关+副屏 | DeviceScreen |

---

## 决策记录

| 日期 | 决定 | 理由 |
|---|---|---|
| 09-13 | 基于 OpenMinis 重做 | v1 npm 打包不可控，OpenMinis 架构成熟 |
| 09-13 | Kotlin + Compose | 类型安全+声明式UI+Google推荐 |
| 09-13 | PRoot 沙箱 | OpenMinis 已验证，Alpine 完整 Linux |
| 09-13 | 保留山野风主题 | 品牌延续 |
| 09-13 | 直连 API 而非 pi 引擎 | 消除 npm 依赖，APK 更小更稳 |
| 09-13 | GitHub Actions 云编译 | NDK 仅 x86_64，Termux aarch64 无法本地编 |
| 09-13 | v1 归档 90le/xiaoqiu-v1 | 孤儿分支快照（剔除162MB bundle），原 xiaoqiu 名让给 v2 |
| 09-14 | **用户评价入档：壳好，底座/UI 要继承 v1** | 用户实测：权限壳做得好，但 agent 底座与页面交互不如 v1 —— 终端无多标签、快捷菜单不如 v1、语音不可用。→ 终端/对话/快捷菜单/语音全链升为 P0 迁移项 |
| 09-14 | 智谱一键模板+OAuth 防炸 | AddProviderScreen 推荐7一键预填；OAuth prompt 空值降级不阻断对话 |

---

> 本文档每完成一个任务即更新。用 ✅⬜🔨❌ 标记状态。

---

## Phase 0 完成记录（2026-09-13）

### CI 迭代史（4 跑过关）

| 跑 | 结果 | 修复 |
|---|---|---|
| 1 | ❌ SDK 步骤挂 | packages 参数改单行空格分隔 |
| 2 | ❌ proot 源缺失 | 注册真子模块（复制丢 gitlink），移除 iOS 专用 ish |
| 3 | ❌ Kotlin 编译挂 | rclone javapkg→com.xiaoqiu.rclone；res 资源名 sed 误伤恢复 minis |
| 4 | ✅ 8m47s 成功 | — |

### 装机验证
- APK 58MB（debug 版，含 Compose+沙箱+jieba 词典+VAD 模型）
- 首启闪退：'native-offload' socket 冲突（原版 OpenMinis 同机运行）→ 卸载原版后正常
- 进程稳定运行 ✅

### 遗留
- [ ] res 资源名仍是 minis（内部实现，不影响显示，改代码引用成本高暂留）
- [ ] libc++_shared.so 重复警告（非致命）
- [ ] 用户首启体验待反馈（rootfs 解压/引导流/界面语言）

| 09-14 | 语音模板加智谱 + Coding/API 双端点 | 用户指正：v1 主用 Coding 订阅端点（coding/paas/v4），非 API 按量。推荐区拆双行；语音=glm-asr+cogtts（OpenAI 兼容免适配器）|
| 09-14 | 规划先行原则 | 用户指示：其它的规划好再开始。PLAN.md 战役制（A 语音→B 终端多标签→C 快捷菜单→D 对话对齐→E 中文→F KWS→G 97工具）|
