# 技术选型

> 本文回答三个问题：**用什么、为什么、什么时候引入**。

## 总览

| 层 | 选择 | 一句话理由 |
|---|---|---|
| 后端语言 | **Java 17**（LTS） | 团队熟悉、企业环境普遍支持、生态成熟 |
| 应用框架 | **Spring Boot 3.4.1** | REST、SSE、配置、数据访问、健康检查一站式；企业开发者熟悉 |
| 构建 | **Maven 多模块 + Maven Wrapper** | 模块边界清晰；Wrapper 保证每个人用同一版本 Maven |
| 存储 | **JSONL 文件**（会话）+ YAML 文件（设置，`~/.liharness/settings.yaml`）+ `.credentials.yaml` 或环境变量（API Key） | 访问模式只有“追加”和“整体读出”；零依赖、可直接查看、可手改；见 [ADR-0010](adr/0010-jsonl-sessions.md)、[ADR-0012](adr/0012-config-home-and-model-registry.md) |
| 桌面外壳 | **Tauri 2（Rust）** | 原生进程管理与本机能力，安装包远小于 Electron |
| 前端 | **React 19 + TypeScript + Vite** | 生态最大；流式 Markdown、代码高亮、图表都有成熟方案 |
| 前后端通信 | **本机 HTTP：REST + SSE，带启动令牌** | 标准协议、可调试、可测试，支持多客户端 |
| 打包 | **jlink 精简 JRE + Tauri bundler** | 用户无需安装 Java |

## 1. 为什么是“Tauri 外壳 + Java 后端”

| 方案 | 不选 / 选的原因 |
|---|---|
| ✅ Tauri 2 + Java 进程 | 智能体逻辑完全在 Java；Rust 只做进程管家与本机能力；界面用 React |
| Electron + Java | 多一个 Node 运行时，安装包大，与 Tauri 相比没有实质收益 |
| JavaFX / Compose Desktop | 单进程、单语言，但放弃 React 生态，聊天界面、Markdown、时序图都要重做 |
| 纯 Web（浏览器访问） | 无法提供进程管理、目录选择、系统通知等原生能力；但我们的后端接口与 Web 完全兼容，将来可以同时支持 |

Tauri 的后端只能是 Rust，所以 Java 以**独立子进程**运行，由 Rust 负责启动、健康检查和终止。详见 [ADR-0001](adr/0001-tauri-shell-java-backend.md)。

## 2. 为什么通信用本机 HTTP（REST + SSE）

| 方案 | 适用场景 | 本项目 |
|---|---|---|
| ✅ 本机 HTTP + SSE + 令牌 | 企业级服务：标准鉴权、日志、链路追踪、接口契约、测试工具都现成；同一后端可服务桌面、CLI、IDE 插件，将来可部署为团队服务 | 采用 |
| stdio JSON-RPC | 一个父进程独占一个子进程的嵌入式工具（LSP、MCP） | 作为备选传输方式，接口层与传输解耦 |
| gRPC | 跨语言微服务 | WebView 里要额外的 grpc-web 代理，收益不大 |
| WebSocket | 高频双向通信 | SSE 已满足“服务端推送 + 普通请求”的模型，更简单 |

前端通过 **Tauri HTTP 插件**发请求（请求由 Rust 发出，不受浏览器跨域限制），后端因此可以完全不开放 CORS。详见 [ADR-0002](adr/0002-loopback-http-sse-token.md)。

## 3. 为什么 Spring Boot 只用在应用层

智能体内核要能在单元测试、评测程序里直接运行；第二阶段的插件只依赖一个稳定、轻量的 SDK。如果内核或插件接口里出现 Spring 注解或 `ApplicationContext`，它们就被绑死在 Spring 和它的版本上了，Spring 升级一次，所有插件都受影响。

所以：

- `liharness-plugin-api`（第 06 章起）、`liharness-core`（第 04 章起）：**纯 Java 17**，除 Jackson、SnakeYAML 外不引入第三方依赖（日志用 JDK 自带的 `System.Logger`，Spring Boot 会把它桥接到 Logback）；
- `liharness-app`：Spring Boot 负责 HTTP、SSE、配置、健康检查，并把内核作为普通 Bean 组装起来。

**AI 框架**（Spring AI、Google ADK 等）不进入内核：模型调用手写在 `infrastructure.adapter.llm` 里（JDK `HttpClient`），以后需要时通过适配器接入，见 [ADR-0009](adr/0009-external-orchestration-via-adapters.md)。

内核和扩展点的划分见 [ADR-0007](adr/0007-fixed-core-extension-points.md)。

## 4. Java 17 的取舍

| 特性 | 可用 | 本项目用法 |
|---|---|---|
| `record` | ✅ | 协议类型、事件、配置 |
| `sealed interface` | ✅ | 事件类型层次、审批结果 |
| `instanceof` 模式匹配 | ✅ | 事件分发 |
| 文本块 `"""` | ✅ | 提示词 |
| `switch` 模式匹配 | ❌（17 里是预览特性） | 用 `if (x instanceof Foo f)` 代替 |
| 虚拟线程 | ❌（21 才正式） | 用有上限的线程池执行 Agent 回合和工具 |
| `java.net.http.HttpClient` | ✅ | 调用模型 API（流式） |
| `ProcessHandle` | ✅ | 父进程看门狗、子进程管理 |

将来升级到 JDK 21，只需把线程池换成虚拟线程执行器，其他代码不变。

> **关于 Spring Boot 3.4.x 的维护周期**：3.4 系列的开源免费维护已经结束，后续安全补丁需要商业支持或升级。教程以 3.4.1 为基线；正式发布前建议评估升级到仍在维护的版本（升级时注意 Spring Boot 4 改用了 Jackson 3）。

## 5. 依赖清单与引入章节

### 后端

| 依赖 | 用途 | 引入章节 |
|---|---|---|
| `spring-boot-starter-web` | REST、SSE（`SseEmitter`） | 01 |
| `jackson-databind` | JSON（Spring Boot 3.4 使用 Jackson 2） | 05（core 模块） |
| `snakeyaml` | 读写 `~/.liharness/settings.yaml`、`.credentials.yaml`（只用 `SafeConstructor`） | 05 |
| JUnit 5 + AssertJ | 测试 | 01 |
| 会话 / 技能 / MCP / 外部插件 | 不引入新依赖：JSONL 文件、`ProcessBuilder`、`URLClassLoader` + Jackson 实现 | 11、13、14、17 |

### 桌面

| 依赖 | 用途 | 引入章节 |
|---|---|---|
| `tauri` 2 | 外壳 | 02 |
| `uuid` | 生成令牌 | 02 |
| 系统命令 `ps` / PowerShell | 回收遗留进程时核对进程命令行（不额外引入依赖） | 02 |
| `reqwest`（Rust 侧） | 健康检查轮询、优雅关闭 | 02 |
| `@tauri-apps/plugin-http` | 前端调用后端 REST / SSE | 03 |
| `@tauri-apps/plugin-dialog` | 新建会话时选择工作区目录 | 11 |
| `@tauri-apps/plugin-opener` / `-notification` | 外部跳转、系统通知 | 19 |
| `react-markdown`、`rehype-highlight` | 渲染模型回答 | 07 |
| `zustand` | 前端状态管理 | 03 |

## 6. 版本基线

| 项 | 版本 |
|---|---|
| JDK | 17 |
| Spring Boot | 3.4.1 |
| Maven | 3.9+（通过 Wrapper） |
| Node.js | 20 LTS 及以上 |
| Rust | stable |
| Tauri | 2.x |
| React | 19 |

> 依赖版本以动手当天实际安装的为准。教程里如果某个 API 变了，欢迎提 [教程反馈](../.github/ISSUE_TEMPLATE/tutorial_feedback.md)。
