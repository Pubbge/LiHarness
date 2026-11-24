# 路线图

每个里程碑对应一章教程，**做完一章打一个 tag**（如 `m01-backend-skeleton`），方便回看每一步的样子。

图例：⬜ 未开始 · 🟨 进行中 · ✅ 完成（教程已全部写完，这里记录**你自己**的实现进度）

## 阶段一：地基（窗口里能看到后端在跑）

| | 里程碑 | 教程 | 交付物 | 验收 |
|---|---|---|---|---|
| ⬜ | M0 环境 | [00](guide/00-setup.md) | JDK 17、Node、Rust、Tauri 依赖 | 版本检查全部通过 |
| ⬜ | M1 后端骨架 | [01](guide/01-backend-skeleton.md) | Maven 多模块、Spring Boot 3.4.1 应用、standalone profile、H2、令牌过滤器、父进程看门狗、关闭接口 | `curl` 带令牌访问 `/api/health` 返回 UP，不带令牌返回 401 |
| ⬜ | M2 桌面骨架 | [02](guide/02-desktop-shell.md) | Tauri 2 + React 19；Rust 进程管家：选端口、运行记录、回收遗留进程、启动、健康轮询、优雅退出 | 打开应用自动拉起后端，窗口显示“后端已就绪”；关闭应用后没有残留 Java 进程 |
| ⬜ | M3 流式通道 | [03](guide/03-streaming.md) | `/api/agent/stream` SSE；Tauri HTTP 插件流式读取；前端事件归一化 | 窗口里逐字显示后端推送的假数据 |

## 阶段二：内核（Agent 能工作）

| | 里程碑 | 教程 | 交付物 | 验收 |
|---|---|---|---|---|
| ⬜ | M4 插件微内核 | [04](guide/04-plugin-kernel.md) | `plugin-api`、`kernel`：生命周期、依赖注入、作用域视图、事件总线 | 单元测试：依赖消失时级联回到 PENDING、注册全部被撤销 |
| ⬜ | M5 协议层与 Mock 模型 | [05](guide/05-protocol-and-mock.md) | `Message`、`ToolCall`、`ChatModel` 服务、`llm-mock` 插件 | Mock 对“几点了”返回 `get_time` 调用 |
| ⬜ | M6 最小 Agent Loop | [06](guide/06-agent-loop.md) | `agent-loop` 插件、三个不变量、保险丝 | 窗口里问“几点了”，看到“调工具 → 回答” |
| ⬜ | M7 工具层 | [07](guide/07-tools.md) | `ToolRegistry`、`tools-builtin`、工作区沙箱 | 工具出错时模型能看到错误并自我修正 |
| ⬜ | M8 对接真模型 | [08](guide/08-openai-model.md) | `llm-openai`：SSE 解析、tool_calls 分片拼接 | 接上真实模型逐字输出；随机切块测试通过 |
| ⬜ | M9 提示词与上下文 | [09](guide/09-prompt-and-context.md) | `ContextAssembler`、token 估算、上下文统计 | 面板显示上下文构成 |

## 阶段三：工程化（靠得住）

| | 里程碑 | 教程 | 交付物 | 验收 |
|---|---|---|---|---|
| ⬜ | M10 执行观测 | [10](guide/10-observability.md) | Trace 事件、执行时序图、停止生成 | 每一步调用实时出现在时序图上 |
| ⬜ | M11 护栏与审批 | [11](guide/11-guardrails-approval.md) | `GuardChain`、`ApprovalGate`、`run_command`、审批卡片 | 白名单外命令弹出审批；120 秒超时自动拒绝 |
| ⬜ | M12 会话持久化 | [12](guide/12-sessions.md) | `session-h2`：追加式事件日志、Flyway、多会话、钥匙串存 Key | 重启后会话还在；可回放一次回合 |
| ⬜ | M13 上下文压缩 | [13](guide/13-compaction.md) | `compaction-core`：阈值摘要、滑动窗口兜底 | 长对话自动压缩且保持连贯 |

## 阶段四：生态（万物皆插件）

| | 里程碑 | 教程 | 交付物 | 验收 |
|---|---|---|---|---|
| ⬜ | M14 外部插件 | [14](guide/14-external-plugins.md) | 插件清单、独立 ClassLoader、安装 / 启用 / 禁用 / 卸载、插件工作台 | 不重启应用，安装一个外部工具插件并被模型调用 |
| ⬜ | M15 MCP | [15](guide/15-mcp.md) | MCP stdio 客户端插件，远端工具桥接 | 连上 MCP 服务器并调用其工具 |
| ⬜ | M16 Skills | [16](guide/16-skills.md) | `SKILL.md` 渐进式披露、`load_skill` 工具 | 命中技能时注入全文 |
| ⬜ | M17 子代理 | [17](guide/17-subagent.md) | `subagent` 工具，独立上下文、防递归 | 子代理中间过程不进入主上下文 |
| ⬜ | M18 本机能力 | [18](guide/18-native-capabilities.md) | Git 状态、目录选择、外部跳转、系统通知、进程诊断（Tauri command） | 会话顶栏显示 Git 分支与改动数；后端挂掉时诊断面板仍可用 |

## 阶段五：交付

| | 里程碑 | 教程 | 交付物 | 验收 |
|---|---|---|---|---|
| ⬜ | M19 打包发布与评测 | [19](guide/19-release-and-eval.md) | jlink 精简 JRE、Tauri 打包、CI 三平台、评测集 | Release 出现三平台安装包；评测输出通过率 |

## 以后再说（v1 之后）

- 插件市场与签名校验
- 不可信插件在独立子进程中运行
- MCP Streamable HTTP 传输、自动更新
- 团队服务器模式（同一后端供多人使用，接入企业 SSO）
- 多 Agent 协作（规划者 / 执行者 / 评审者）
- 升级到 JDK 21+ 并切换虚拟线程
