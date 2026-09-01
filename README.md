# piark-workbench · pi 超级app 工作台

> **定位：AI 工作台——不是又一个终端。**
> 终端只是集成的能力模块之一；产品的中心是「AI 替用户把事办成」。

## 工作台七页架构

| 页面 | 说明 |
|---|---|
| 🏠 首页·AI 对话 | 自然语言即操控（小白 90% 场景） |
| ⚡ 场景卡片 | 一键清理/备份/装应用/短信摘要/定时任务 |
| 🖥 终端 | 完整 PTY（能力模块，非产品身份） |
| 📁 文件 | 文件管理 + AI 操作 |
| 🔧 工具 | 33 项手机原生能力（MCP 工具面板） |
| 🧩 技能市场 | Agent Skills 标准（markdown 技能包） |
| ⚙ 设置 | API Key / 模型 / 权限向导 / 中英双语 |

## 技术构成

- **MCP 服务器内嵌**：127.0.0.1:8099，Streamable HTTP，33 工具（学 MT 管理器）
- **pi 环境**：pi 风味 bootstrap（[piark releases](https://github.com/90le/piark/releases)）提供 node + pi coding-agent
- **构建**：无 Gradle，`bash build.sh`（aapt → javac → d8 → apksigner），本机 Termux 与 GitHub CI 通用

## 构建

```bash
bash build.sh   # 产物 build/pibridge.apk
```

CI：推送即构建，产物在 Actions Artifacts。

## 相关仓库

- [90le/piark](https://github.com/90le/piark) — pi 终端环境 fork（pi 风味 bootstrap 载体）
- [90le/pi-api](https://github.com/90le/pi-api) — Termux:API 插件 vendor 化

## 许可

GPL-3.0
