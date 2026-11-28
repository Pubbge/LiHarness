# 路线图

每个里程碑对应一章教程，**做完一章打一个 tag**（如 `m06-agent-loop`），方便回看每一步的样子。

图例：⬜ 未开始 · 🟨 进行中 · ✅ 完成（这里记录**你自己**的实现进度）

![两阶段路线](images/phases.svg)

## 第一阶段：最小可用 Harness（MVP）

目标：一个能日常使用的桌面 Agent。没有插件系统，所有能力写在 `liharness-core` 里，但按“接口 + 实现”组织，为第二阶段留好边界。

### 地基：窗口里能看到后端在跑

| | 里程碑 | 教程 | 交付物 | 验收 |
|---|---|---|---|---|
| ✅ | M0 环境 | [00](guide/00-setup.md) | JDK 17、Node、Rust、Tauri 依赖 | 版本检查全部通过 |
| ✅ | M1 后端骨架 | [01](guide/01-backend-skeleton.md) | Maven 多模块、Spring Boot 3.4.1、standalone profile、令牌过滤器、父进程看门狗、关闭接口 | 带令牌访问 `/api/health` 返回 UP，不带令牌返回 401 |
| ✅ | M2 桌面骨架 | [02](guide/02-desktop-shell.md) | Tauri 2 + React 19；Rust 进程管家 | 打开应用自动拉起后端；关闭后没有残留 Java 进程 |
| ✅ | M3 流式通道 | [03](guide/03-streaming.md) | SSE、回合线程池、Tauri HTTP 插件、前端事件归一化 | 窗口里逐字显示后端推送的演示回答 |

### Agent 核心：能调工具的 Agent

| | 里程碑 | 教程 | 交付物 | 验收 |
|---|---|---|---|---|
| ⬜ | M4 模型端口与 Mock 模型 | [04](guide/04-model-protocol.md) | 新建 `liharness-core`；分层与包名、架构测试；协议数据（`Message`、`ToolCall`…）；`LlmChatModelAdapter` + 拉取式 `LlmStream` + 细粒度 `StreamChunk`；`ResponseAssembler`；`MockLlmAdapter`；取消令牌 | Mock 逐字回答；点停止立即结束 |
| ⬜ | M5 接入真实模型 | [05](guide/05-real-model.md) | 配置目录 `~/.liharness`（`settings.yaml` + `.credentials.yaml`）；模型注册表（内置 provider + 用户覆盖、按协议注册适配器）；OpenAI 兼容流式适配器（推转拉、错误分类、空闲超时） | 改一行 `settings.yaml` 就接上 DeepSeek 等真实模型逐字输出，不用重启 |
| ⬜ | M6 最小 Agent Loop | [06](guide/06-agent-loop.md) | `AgentLoop`：不变量、最大步数、取消；`liharness-plugin-api` 的 `Tool` 接口；接入 SSE | 窗口里问“几点了”，看到“调工具 → 回答” |
| ⬜ | M7 工具系统 | [07](guide/07-tools.md) | 工具表、Schema 校验、输出截断；fs 五件套、`run_command`、`web_fetch`；工作区沙箱 | 工具出错时模型能看到错误并自我修正；`../` 越界被拒绝 |
| ⬜ | M8 模型设置、重试与思考过程 | [08](guide/08-model-settings.md) | 自动重试装饰器（`Retry` 片段）；思考过程显示；设置面板（读写同一组 YAML，保留注释、原子写入、Key 文件 600） | 面板里保存 Key、测试连接；限流时自动重试并有提示 |

### 工程化：靠得住

| | 里程碑 | 教程 | 交付物 | 验收 |
|---|---|---|---|---|
| ⬜ | M9 执行观测 | [09](guide/09-observability.md) | 回合日志、耗时与用量、停止接口、诊断信息 | 日志里能还原每一步；诊断信息一键复制 |
| ⬜ | M10 护栏与审批 | [10](guide/10-guardrails.md) | 权限规则（deny > ask > allow）、权限模式、危险命令拦截、审批卡片 | 规则外的命令弹出审批；危险命令无论如何都被拦 |
| ⬜ | M11 会话持久化 | [11](guide/11-sessions.md) | JSONL 会话文件（只追加、崩溃容忍、历史修复）；多会话与工作区 | 重启后会话还在；强制结束后端后会话依然能用 |
| ⬜ | M12 上下文管理 | [12](guide/12-context.md) | token 估算与校准、清理工具输出、摘要压缩、系统提示词段落、项目说明文件、用量条 | 窗口调小后长对话自动压缩并继续 |

### 进阶能力

| | 里程碑 | 教程 | 交付物 | 验收 |
|---|---|---|---|---|
| ⬜ | M13 技能、待办与子代理 | [13](guide/13-skills-todo-subagent.md) | `SKILL.md` 渐进式披露、`skill` 工具；`todo_write` 与待办面板；`task` 子代理 | 命中技能时按说明执行；子代理过程不进入主上下文 |
| ⬜ | M14 MCP 接入 | [14](guide/14-mcp.md) | JSON-RPC 连接、stdio MCP 客户端、`mcp__服务器__工具`、后台启动与状态面板 | 连上官方 filesystem 服务器并调用其工具 |

**🎯 v0.1 MVP**：第一阶段完成。可以本地打包自用（第 20 章的打包脚本提前用也可以）。

## 第二阶段：完整 Harness

目标：从“一个 Agent”变成“一个可扩展的平台”。内核保持不变，开放扩展点。每一章的详细需求见 [第二阶段需求](phase2-requirements.md)。

| | 里程碑 | 教程 | 交付物 | 验收 |
|---|---|---|---|---|
| ⬜ | M15 多模型管理 | 15 | 在第 05 章的模型注册表上：每个会话单独选模型、界面里管理自定义 provider、可选的备用模型 | 同时配置 3 个模型，会话间切换无需重启 |
| ⬜ | M16 插件 SDK 与扩展点 | 16 | 扩展第 06 章的 `liharness-plugin-api`；工具、提示词段落、钩子、事件订阅、技能五个扩展点；内置工具迁到同一接口 | 一个内置“插件”通过扩展点注册工具和钩子，停用后全部撤销 |
| ⬜ | M17 插件宿主 | 17 | 外部插件 jar、清单与版本检查、独立类加载器、启用 / 停用、插件工作台、示例插件 | 不重启应用，安装一个外部插件并被模型调用 |
| ⬜ | M18 插件市场 | 18 | 插件目录、下载、SHA-256 校验、更新与卸载 | 在界面里浏览并安装一个插件 |
| ⬜ | M19 原生能力 | 19 | 目录选择、通知、Git 状态、诊断面板、登录 shell 的 PATH | 后端挂掉时诊断面板仍可用 |
| ⬜ | M20 打包发布与评测 | 20 | jlink 精简 JRE、三平台 CI、评测集 | Release 出现三平台安装包；评测输出通过率 |

**🎯 v1.0**：第二阶段完成。

## 以后再说

- 插件签名与可信发布者
- MCP Streamable HTTP 传输、自动更新
- 团队服务器模式（同一后端供多人使用，接入企业 SSO）
- 多 Agent 协作（规划者 / 执行者 / 评审者），接入 Google ADK 等编排框架（第三阶段，见 [ADR-0009](adr/0009-external-orchestration-via-adapters.md) 和第二阶段需求文档的最后一节）
- 升级到 JDK 21+ 并切换虚拟线程
