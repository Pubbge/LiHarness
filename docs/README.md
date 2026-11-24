# 文档总入口

## 从哪里开始

```text
1. 读 architecture.md 的第 1–5 节，知道整体长什么样（15 分钟）
2. 从 guide/00-setup.md 开始，一章一章往下做
3. 遇到“为什么要这样设计”，查 adr/ 和 plugin-architecture.md
```

## 文档地图

| 文档 | 什么时候看 |
|---|---|
| [architecture.md](architecture.md) | 想知道“我写的这块在整体的哪里” |
| [plugin-architecture.md](plugin-architecture.md) | 写插件、改内核之前 |
| [tech-stack.md](tech-stack.md) | 想知道“为什么用这个库、哪一章引入” |
| [roadmap.md](roadmap.md) | 看进度、打勾 |
| [adr/](adr/README.md) | 想知道某个关键设计当时为什么这么定 |
| [images/](images/) | 架构图（SVG），可以直接用在你自己的分享里 |
| [guide/](#教程章节) | **主线**，按顺序做 |

## 教程章节

| 章 | 标题 | 主要知识点 | 状态 |
|---|---|---|---|
| [00](guide/00-setup.md) | 环境准备 | JDK、Maven Wrapper、Node、Rust、Tauri 依赖 | ✅ |
| [01](guide/01-backend-skeleton.md) | 后端骨架 | Maven 多模块、Spring Boot profile、令牌过滤器、`ProcessHandle` 看门狗、优雅关闭 | ✅ |
| [02](guide/02-desktop-shell.md) | 桌面骨架与 Rust 进程管家 | Tauri 2、`TcpListener`、原子写文件、进程回收、健康轮询、退出钩子 | ✅ |
| [03](guide/03-streaming.md) | 流式通道 | `SseEmitter`、Tauri HTTP 插件、`ReadableStream`、事件归一化 | ✅ |
| [04](guide/04-plugin-kernel.md) | 插件微内核 | 生命周期状态机、依赖编排、作用域视图、事件总线 | ✅ |
| [05](guide/05-protocol-and-mock.md) | 协议层与 Mock 模型 | `record`、`sealed interface`、Jackson | ✅ |
| [06](guide/06-agent-loop.md) | 最小 Agent Loop | 不变量、保险丝、线程池、取消 | ✅ |
| [07](guide/07-tools.md) | 工具层 | 工作区沙箱、`Path.toRealPath`、错误即观察 | ✅ |
| [08](guide/08-openai-model.md) | 对接真模型 | `HttpClient` 流式、SSE 解析、分片拼接 | ✅ |
| [09](guide/09-prompt-and-context.md) | 提示词与上下文 | 上下文组装、token 估算 | ✅ |
| [10](guide/10-observability.md) | 执行观测 | Trace 事件、时序图 | ✅ |
| [11](guide/11-guardrails-approval.md) | 护栏与审批 | 策略链、`CompletableFuture` 超时、子进程 | ✅ |
| [12](guide/12-sessions.md) | 会话持久化与密钥 | Flyway、事件日志、钥匙串 | ✅ |
| [13](guide/13-compaction.md) | 上下文压缩 | 摘要与降级 | ✅ |
| [14](guide/14-external-plugins.md) | 外部插件与热插拔 | `URLClassLoader`、插件清单、卸载与回收 | ✅ |
| [15](guide/15-mcp.md) | MCP 接入 | 子进程 stdio、JSON-RPC | ✅ |
| [16](guide/16-skills.md) | 技能系统 | 渐进式披露 | ✅ |
| [17](guide/17-subagent.md) | 子代理 | 独立上下文、防递归 | ✅ |
| [18](guide/18-native-capabilities.md) | 原生能力 | Tauri command、dialog / opener / notification 插件 | ✅ |
| [19](guide/19-release-and-eval.md) | 打包发布与评测 | jlink、Tauri bundler、CI、评测集 | ✅ |

## 每章的固定结构

1. **本章目标**：做完能看到什么
2. **先想清楚：为什么**：这块在系统里解决什么问题
3. **知识点**：这一章新出现的语言特性、框架机制
4. **跟着写**：分步骤，每一步都有“写什么”和“为什么这么写”
5. **验收标准**：怎么确认自己做对了
6. **常见坑**：报错和解决办法
7. **练习**：不给答案，自己想
