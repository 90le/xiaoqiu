# 小丘 v2 · 进度跟踪

> 最后更新：2026-09-13
> 当前阶段：Phase 0（环境搭建）

---

## 总体进度

| 阶段 | 状态 | 开始 | 完成 | 进度 |
|---|---|---|---|---|
| Phase 0 环境搭建 | 🔨 进行中 | 09-13 | — | 60% |
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
| 0.8 | 配置 Android SDK | ⬜ | Termux 或 GitHub Actions |
| 0.9 | 第一次编译通过 | ⬜ | 阻塞项 |
| 0.10 | 推送 GitHub | ⬜ | 网络问题待解 |

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

---

> 本文档每完成一个任务即更新。用 ✅⬜🔨❌ 标记状态。
