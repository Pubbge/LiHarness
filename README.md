<div align="center">

# LiHarness

**可扩展的桌面 AI Agent Harness**

Java 智能体内核 · Tauri 2 原生外壳 · React 本地控制台

[![Status](https://img.shields.io/badge/status-WIP-orange)](docs/roadmap.md)
[![Java](https://img.shields.io/badge/Java-17%2B-007396?logo=openjdk)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.1-6DB33F?logo=springboot)](https://spring.io/projects/spring-boot)
[![Tauri](https://img.shields.io/badge/desktop-Tauri%202-24c8db?logo=tauri)](https://v2.tauri.app/)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

[这是什么](#这是什么) · [架构](#架构) · [技术栈](#技术栈) · [学习路线](#学习路线) · [路线图](docs/roadmap.md) · [参与贡献](CONTRIBUTING.md)

</div>

---

## 这是什么

LiHarness 是一个跑在桌面上的 **AI Agent Harness（智能体运行框架）**，同时也是一套从零实现它的教程。

Harness 是包在大模型外面的那层运行时：组装上下文、调用模型、执行工具、守护安全边界、管理会话与记忆。Anthropic 在 [Building effective agents](https://www.anthropic.com/engineering/building-effective-agents) 里总结过：表现好的 Agent 往往不依赖复杂框架，本质是**模型在循环里根据环境反馈调用工具**。

LiHarness 分两个阶段实现：

1. **第一阶段：最小可用 Harness（MVP）**。一个能用的桌面 Agent：流式对话、工具、护栏与审批、多会话、上下文管理、技能、子代理、MCP。
2. **第二阶段：完整 Harness**。在 MVP 之上开放扩展点：插件 SDK、外部插件热插拔、插件市场、多模型管理。

设计原则是**固定内核 + 扩展点插件**：模型适配、Agent 循环、会话存储、上下文管理、护栏与审批是稳定的内核；插件通过工具、提示词段落、钩子、事件订阅、技能这几个扩展点增加能力，但不替换内核。

## 核心特性

| 能力 | 说明 | 阶段 |
|---|---|---|
| 🔁 Agent Loop | 模型 ⇄ 工具循环，带不变量校验与最大步数保险丝 | 一 |
| 🌊 流式输出 | OpenAI 兼容协议，SSE 增量推送文本、工具调用、审批请求与错误 | 一 |
| 🛠️ 内置工具 | 文件读写搜索、命令执行、网页获取、计算器等，工作区沙箱 | 一 |
| 🛡️ 护栏与审批 | 策略链 + 人工审批（human-in-the-loop），白名单外的命令必须用户批准 | 一 |
| 📊 全程可观测 | 工具卡片与进度、回合日志、token 用量与上下文水位、诊断信息 | 一 |
| 💾 多会话 | JSONL 文件，只追加、崩溃容忍，每个会话独立的工作区 | 一 |
| 🧠 上下文工程 | 提示词段落、项目说明文件、token 估算与校准、两级压缩 | 一 |
| 🔌 技能 / 待办 / 子代理 / MCP | 渐进式披露的技能、待办清单、独立上下文的子代理、MCP 服务器接入 | 一 |
| 🧩 插件 SDK | 工具、提示词段落、钩子、事件订阅、技能五个扩展点；停用时自动撤销 | 二 |
| 📦 插件市场 | 外部插件 jar 热插拔、类加载隔离、目录与校验 | 二 |
| 🗂️ 多模型 | 模型列表、默认模型、每个会话单独选择 | 二 |
| 🖥️ 原生桌面 | Tauri 2 负责进程生命周期与本机能力，前端不直接暴露命令执行面 | 一 / 二 |

## 架构

![LiHarness 总体架构](docs/images/architecture-overview.svg)

一句话：**智能体逻辑全部在 Java，桌面外壳只负责进程生命周期、本机能力和流式交互；Java 后端是固定内核 + 扩展点插件。** 详见 [架构设计](docs/architecture.md) 与 [插件与扩展点](docs/plugin-architecture.md)。

## 技术栈

| 层 | 选择 |
|---|---|
| 后端语言 | Java 17（LTS） |
| 应用框架 | Spring Boot 3.4.1（只用于应用层，智能体内核与插件 SDK 不依赖 Spring） |
| 构建 | Maven 多模块 + Maven Wrapper |
| 存储 | JSONL 文件（会话）+ JSON 文件（设置）+ 系统钥匙串（API Key） |
| 桌面外壳 | Tauri 2（Rust） |
| 前端 | React 19 + TypeScript + Vite |
| 通信 | 本机 HTTP（REST + SSE），每次启动生成令牌 |
| 打包 | jlink 精简 JRE + Tauri bundler |

选型理由见 [技术选型](docs/tech-stack.md) 与 [架构决策记录](docs/adr/README.md)。

## 学习路线

教程在 [`docs/guide/`](docs/guide/)，每章结构固定：**本章目标 → 为什么 → 知识点 → 跟着写 → 验收标准 → 常见坑 → 练习**。

| 阶段 | 章节 |
|---|---|
| 一 · 地基 | [00 环境准备](docs/guide/00-setup.md) · [01 后端骨架](docs/guide/01-backend-skeleton.md) · [02 桌面骨架与 Rust 进程管家](docs/guide/02-desktop-shell.md) · [03 流式通道](docs/guide/03-streaming.md) |
| 一 · Agent 核心 | [04 模型端口与 Mock 模型](docs/guide/04-model-protocol.md) · [05 最小 Agent Loop](docs/guide/05-agent-loop.md) · [06 工具系统](docs/guide/06-tools.md) · [07 对接真模型](docs/guide/07-openai-model.md) |
| 一 · 工程化 | [08 执行观测](docs/guide/08-observability.md) · [09 护栏与审批](docs/guide/09-guardrails.md) · [10 会话持久化](docs/guide/10-sessions.md) · [11 上下文管理](docs/guide/11-context.md) |
| 一 · 进阶能力 | [12 技能、待办与子代理](docs/guide/12-skills-todo-subagent.md) · [13 MCP 接入](docs/guide/13-mcp.md) → **v0.1 MVP** |
| 二 · 可扩展平台 | 14 多模型管理 · 15 插件 SDK 与扩展点 · 16 插件宿主 · 17 插件市场 |
| 二 · 交付 | 18 原生能力 · 19 打包发布与评测 → **v1.0**（每章要做什么见 [第二阶段需求](docs/phase2-requirements.md)） |

> 第一阶段（第 00–13 章）已全部写完，第二阶段的需求已整理好，进度见 [路线图](docs/roadmap.md)。每章末尾的 git tag（如 `m05-agent-loop`）可以作为进度检查点。

## 目录结构

```text
LiHarness/
├── pom.xml                          # Maven 父工程
├── backend/
│   ├── liharness-plugin-api/        # 对外契约（只依赖 JDK）：Tool 接口；（二）Plugin、PluginContext、HookPoint
│   ├── liharness-core/              # 智能体内核（纯 Java）：模型、Agent 循环、工具、权限、会话、上下文、技能、MCP
│   └── liharness-app/               # Spring Boot 应用：REST + SSE、设置与钥匙串、组装内核；（二）插件宿主
├── desktop/                         # Tauri 2 应用
│   ├── src/                         # React 19 + TypeScript
│   └── src-tauri/                   # Rust 进程管家与原生能力
├── examples/hello-plugin/           # （二）外部插件示例（独立 Maven 工程）
├── scripts/                         # 构建运行时、设置版本号
└── docs/                            # 架构、ADR、教程、架构图
```

## 快速开始

> 代码随教程逐章实现，以下命令在对应章节完成后可用。

```bash
# 后端单独运行（第 01 章起）
./mvnw -pl backend/liharness-app -am spring-boot:run

# 桌面应用（第 02 章起）
cd desktop && npm install && npm run tauri dev
```

## 安全

LiHarness 会让模型在你的电脑上读写文件、执行命令。后端只监听 `127.0.0.1`，每次启动生成随机令牌，所有危险操作经过护栏与人工审批。发现安全问题请按 [SECURITY.md](SECURITY.md) 私下报告。

## 参与贡献

欢迎 Issue 和 PR，尤其是教程勘误、各平台的踩坑记录，以及第二阶段之后的新插件。开始前请阅读 [CONTRIBUTING.md](CONTRIBUTING.md) 与 [行为准则](CODE_OF_CONDUCT.md)。

## 致谢

- Anthropic：[Building effective agents](https://www.anthropic.com/engineering/building-effective-agents)、[Effective context engineering for AI agents](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)
- [Tauri](https://tauri.app/)、[Spring Boot](https://spring.io/projects/spring-boot)、[Model Context Protocol](https://modelcontextprotocol.io/)

## 许可证

[MIT](LICENSE) © 2026 Pubbge
