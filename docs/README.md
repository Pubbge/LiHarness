# 文档总入口

## 从哪里开始

```text
1. 读 architecture.md 的第 1–5 节，知道整体长什么样、为什么分两个阶段（15 分钟）
2. 从 guide/00-setup.md 开始，一章一章往下做
3. 遇到“为什么要这样设计”，查 adr/；做到第二阶段时读 plugin-architecture.md
```

## 文档地图

| 文档 | 什么时候看 |
|---|---|
| [architecture.md](architecture.md) | 想知道“我写的这块在整体的哪里” |
| [plugin-architecture.md](plugin-architecture.md) | 想知道插件能做什么、不能做什么（第二阶段） |
| [tech-stack.md](tech-stack.md) | 想知道“为什么用这个库、哪一章引入” |
| [roadmap.md](roadmap.md) | 看进度、打勾 |
| [phase2-requirements.md](phase2-requirements.md) | 第一阶段做完后，看第二阶段每一章要做什么 |
| [adr/](adr/README.md) | 想知道某个关键设计当时为什么这么定 |
| [images/](images/) | 架构图（SVG），可以直接用在你自己的分享里 |
| [guide/](#教程章节) | **主线**，按顺序做 |

## 教程章节

**第一阶段：最小可用 Harness（MVP）** 是第 00–13 章，**第二阶段：完整 Harness** 是第 14–19 章。为什么分两阶段，见 [ADR-0008](adr/0008-two-phase-delivery.md)。

| 阶段 | 章 | 标题 | 主要知识点 | 状态 |
|---|---|---|---|---|
| 一 | [00](guide/00-setup.md) | 环境准备 | JDK、Maven Wrapper、Node、Rust、Tauri 依赖 | ✅ |
| 一 | [01](guide/01-backend-skeleton.md) | 后端骨架 | Maven 多模块、Spring Boot profile、令牌过滤器、`ProcessHandle` 看门狗、优雅关闭 | ✅ |
| 一 | [02](guide/02-desktop-shell.md) | 桌面骨架与 Rust 进程管家 | Tauri 2、`TcpListener`、原子写文件、进程回收、健康轮询、退出钩子 | ✅ |
| 一 | [03](guide/03-streaming.md) | 流式通道 | `SseEmitter`、回合线程池、Tauri HTTP 插件、事件归一化 | ✅ |
| 一 | [04](guide/04-model-protocol.md) | 模型端口与 Mock 模型 | 分层与包名、`record` 不变量、拉取式流 `LlmStream`、细粒度片段与领域层拼装、取消 = 关闭流、架构测试 | ✅ |
| 一 | [05](guide/05-agent-loop.md) | 最小 Agent Loop | 不变量、保险丝、事件、`plugin-api` 的 `Tool` 接口 | ✅ |
| 一 | [06](guide/06-tools.md) | 工具系统 | 工具表、Schema 校验、规则与机制分离（文件 / 命令 / 网页端口）、工作区沙箱、错误即观察、进程树 | ✅ |
| 一 | [07](guide/07-openai-model.md) | 对接真模型 | `HttpClient` 流式、推转拉、tool_calls 分片拼接、重试与退避、钥匙串 | ✅ |
| 一 | [08](guide/08-observability.md) | 执行观测 | 回合日志、用量与耗时、停止、诊断信息 | ✅ |
| 一 | [09](guide/09-guardrails.md) | 护栏与审批 | 规则优先级、权限模式、`CompletableFuture` 审批 | ✅ |
| 一 | [10](guide/10-sessions.md) | 会话持久化 | JSONL 只追加、`force()`、崩溃容忍、历史修复、路径穿越 | ✅ |
| 一 | [11](guide/11-context.md) | 上下文管理 | token 估算与校准、两级压缩、切分点、提示词段落、项目说明 | ✅ |
| 一 | [12](guide/12-skills-todo-subagent.md) | 技能、待办与子代理 | 渐进式披露、无状态工具、嵌套 Agent Loop、取消传递 | ✅ |
| 一 | [13](guide/13-mcp.md) | MCP 接入 | JSON-RPC、stdio、读线程与 pending 表、管道缓冲区、代数 | ✅ |
| 二 | 14 | 多模型管理 | 注册表、默认值与覆盖 | 待编写 |
| 二 | 15 | 插件 SDK 与扩展点 | 扩展点设计、钩子、自动撤销 | 待编写 |
| 二 | 16 | 插件宿主 | `URLClassLoader`、清单、热插拔、工作台 | 重写中 |
| 二 | 17 | 插件市场 | 目录、下载、校验 | 待编写 |
| 二 | 18 | 原生能力 | Tauri command、dialog / opener / notification | 重写中 |
| 二 | 19 | 打包发布与评测 | jlink、Tauri bundler、CI、评测集 | 重写中 |

第二阶段的章节还没有写，每一章要做什么已经整理在 [第二阶段需求](phase2-requirements.md) 里。“重写中”的章节以旧版教程为基础调整；旧版放在本地的 `guide/archive-v1/`，不上传。

## 每章的固定结构

1. **本章目标**：做完能看到什么
2. **先想清楚：为什么**：这块在系统里解决什么问题
3. **知识点**：这一章新出现的语言特性、框架机制
4. **跟着写**：分步骤，每一步都有“写什么”和“为什么这么写”
5. **验收标准**：怎么确认自己做对了
6. **常见坑**：报错和解决办法
7. **练习**：不给答案，自己想
