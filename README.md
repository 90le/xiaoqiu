# 小丘 — 手机 AI 工作台

> **你说，我来办。**
> 一个独立 App：内置完整 Linux 环境 + pi 编码智能体 + 手机原生操控（MCP）+ 工作台界面。
> 下载 → 安装 → 填 API Key → 小丘住进你的手机。

## 定位

**不是又一个终端**——是 AI 工作台。终端只是集成的能力模块之一；产品的中心是「AI 替用户把事办成」。

| 页面 | 说明 |
|---|---|
| 🏠 首页·AI 对话 | 自然语言即操控（小白 90% 场景） |
| ⚡ 场景卡片 | 一键清理/备份/装应用/短信摘要/定时任务 |
| 🖥 终端 | 完整 PTY（能力模块，非产品身份） |
| 📁 文件 | 文件管理 + AI 操作 |
| 🔧 工具 | 37+ 手机原生能力（MCP 工具面板） |
| 🧩 技能市场 | Agent Skills 标准（markdown 技能包） |
| ⚙ 设置 | API Key / 模型 / 权限向导 / 中英双语 |

## 技术构成

- **MCP 服务器内嵌**：127.0.0.1:8181（`/mcp`），37 工具：系统调用 + 无障碍感知（读屏/截图/文本直设）+ 环境引擎
- **环境引擎**：首启自动安装 pi 风味 bootstrap（[piark releases](https://github.com/90le/piark/releases)），node v26 + pi coding-agent 开箱即用
- **感知三件套**：ui_screen_read（控件树）/ ui_set_text（文本直设）/ screenshot（无障碍截图）
- **构建**：无 Gradle，`bash build.sh`（aapt → javac → d8 → apksigner），Termux 本机与 GitHub CI 通用

## 操作纪律（AI 行为准则）

L1 系统调用（Intent/Provider/shell）→ L2 特权通道 → L3 无障碍 UI 模拟。能用底层绝不模拟点击。

## 相关仓库

- [90le/piark](https://github.com/90le/piark) — pi 终端环境 fork（pi 风味 bootstrap 载体）
- [90le/pi-api](https://github.com/90le/pi-api) — Termux:API 插件 vendor 化

## 许可

GPL-3.0 · 引擎：[pi coding-agent](https://www.npmjs.com/package/@earendil-works/pi-coding-agent)
