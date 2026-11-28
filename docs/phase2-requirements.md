# 第二阶段需求：完整 Harness

> 第一阶段（第 00–14 章）完成后读这份文档。它说明第二阶段每一章**要做什么、为什么、怎么验收**，以及第一阶段的代码在哪里已经留好了接口。教程正文等你做完第一阶段再写，写之前可以根据你的使用体验调整这里的需求。

## 0. 总目标与原则

第一阶段结束时，LiHarness 是一个**能用的桌面 Agent**：一个模型、内置工具、权限、会话、上下文、技能、子代理、MCP。第二阶段把它变成一个**可扩展的平台**：

```text
第一阶段（v0.1）                         第二阶段（v1.0）
一个模型配置                    ──►      模型列表，每个会话可选，失败时可切备用模型        （14）
能力写在 core 里                ──►      插件 SDK：工具 / 提示词段落 / 钩子 / 事件 / 技能   （15）
                                ──►      外部插件 jar：安装、启用、停用、热替换             （16）
                                ──►      插件市场：浏览、下载、校验、更新                   （17）
桌面只负责启动后端              ──►      通知、打开文件、Git 状态、诊断、登录 shell 的 PATH （18）
开发时运行                      ──►      三平台安装包 + 评测集 + CI                        （19）
```

三条原则不变：

1. **固定内核**：模型调用、Agent Loop、会话存储、上下文管理、权限与审批的主流程，插件不能替换（[ADR-0007](adr/0007-fixed-core-extension-points.md)）。
2. **core 不依赖 Spring，plugin-api 只依赖 JDK**。第一阶段已经用 Maven Enforcer 和两个架构测试（`ArchitectureTest`、`AppArchitectureTest`）守住了；第 16 章规则变多时可以换成 ArchUnit。
3. **每一章结束都是能用的产品**，不留“下一章才能跑”的半成品。

### 第一阶段已经留好的接缝

| 接缝（第一阶段） | 在哪 | 第二阶段怎么用 |
|---|---|---|
| `LlmChatModelAdapter` / `LlmStream` / `LlmGenerationService` | `domain.llm` | 15：多个模型、备用模型都是 `LlmChatModelAdapter` 的组合（像 `RetryingLlmPort` 一样的装饰器）；也是接入 Spring AI 的位置（[ADR-0009](adr/0009-external-orchestration-via-adapters.md)） |
| `LlmProviderRegistry` + `ILlmAdapterFactory` | `domain.llm.service`、`domain.llm.adapter.port`（实现 `infrastructure.adapter.llm.*.…AdapterFactory`） | 15：已经支持多个 provider、`provider/model`；第二阶段加会话级选择、备用模型、新协议 |
| `Tool` 在 `liharness-plugin-api` 里 | `domain.spi.tool` | 16：插件工具和内置工具同一个接口 |
| `TurnTools.forTurn()` 每回合组装工具 | `cases.agent` | 16：加上已启用插件的工具 |
| `SystemPromptBuilder` 段落 + 顺序空位 | `domain.context.service.prompt` | 16：插件插入段落（500–800） |
| `ToolGate` 规则链 | `domain.permission.service` | 16：`PRE_TOOL_USE` 钩子包装成一个 gate |
| `ITurnEventListener.compose()` | `domain.agent.contract.outbound` | 16：事件订阅 |
| `SkillCatalog.load(files, userDir, workspace)` | `domain.skill.service` | 16：再加上插件附带的技能 |
| `McpServerRegistry` 的“代数” | `domain.tool.mcp.service` | 17：插件的启用 / 停用用同样的方法处理过期的异步结果 |
| 诊断信息 | `trigger.http.DiagnosticsController` | 19：Rust 侧的诊断在后端挂掉时也能用 |

---

## 第 15 章 · 多模型管理

### 目标

第一阶段的模型注册表（第 05 章）已经能配置多个 provider、用 `provider/model` 指定模型，但全局只有一个默认模型。这一章让每个会话可以单独选模型（比如 DeepSeek 做日常、Qwen 做长上下文、本地 Ollama 离线用）；主模型暂时不可用时自动切换到备用模型；再加一种协议。

### 需求

| # | 需求 | 说明 |
|---|---|---|
| 15.1 | 模型能力 | `settings.yaml` 的模型配置加“能力”字段：`supportsTools`（不支持工具调用的模型，回合里不发工具）、`supportsReasoning`、`supportsImages`（预留） |
| 15.2 | 默认模型 | 保持第 05 章的全局 `defaultModel`；新会话用它 |
| 15.3 | 会话级选择 | 会话元数据记录 `model`（`provider/model`）；对话框上方可以切换，切换后从下一个回合生效。会话记录的模型不存在了（provider 被删、模型不在列表里）时回退到默认模型，并在界面上提示 |
| 15.4 | 界面管理 provider | 设置面板里添加、修改、删除自定义 provider（写进 `settings.yaml` 的 `providers` 段，用第 08 章的 `YamlSections` 保留注释）；删除 provider 时询问是否同时删除 `.credentials.yaml` 里的 Key |
| 15.5 | 备用模型 | 每个模型可以指定一个备用模型（`fallback: provider/model`）。主模型返回可重试错误（限流、过载、网络）且重试用尽时，**还没有输出任何内容之前**切换到备用模型重试这一步；界面上显示“已切换到 xxx” |
| 15.6 | Anthropic 原生协议 | 新协议 `anthropic-messages`：一个新的 `ILlmAdapterFactory` 和适配器，注册到 `LlmConfig` 的列表里；内置 provider 目录加上 `anthropic` |
| 15.7 | 列出服务端的模型 | 对 OpenAI 兼容服务调用 `GET /models`，在设置面板的模型输入框里给出提示（失败时静默，不影响手动输入） |
| 15.8 | 用量按模型统计 | 回合结束时的用量记录 `provider/model`；诊断信息里显示本次运行每个模型的累计用量 |

### 设计要点

- `LlmProviderRegistry` 不用推倒重来：`resolve(ModelRef)` 本来就和“默认模型”无关，会话级选择只是在 `ModelCase` 里多一条规则：“会话有 `model` 用会话的，没有用默认的”。这正是第 05 章把“选哪个模型”放进用例的原因。
- 备用模型是一个组合：`FallbackLlmPort(primary, fallback)` 也实现 `LlmChatModelAdapter`，和第 08 章的 `RetryingLlmPort` 是同一种写法（包装一个流）。**切换条件和重试条件一样：只在还没交出内容前切换**，否则用户会看到两段拼在一起的回答。
- 上下文策略（第 12 章）按“本回合实际使用的模型”计算窗口；切换到窗口更小的备用模型时，`fit()` 可能需要再压缩一次。
- `TokenEstimator` 已经按 `provider/model` 分开（第 12 章的 `ContextPolicyFactory`），不用改。

### 不做

- 按任务自动选模型（路由）。可以作为练习：根据输入长度选长上下文模型。
- 系统钥匙串存 Key。第 05 章的练习 2 可以先做：`ICredentialRepository` 换一个实现，其他代码不用改。

### 验收

- 配置 3 个模型，两个会话分别用不同模型，切换不需要重启
- 把主模型地址改错，回合自动切到备用模型并提示
- 在设置面板添加一个自定义 provider 后，`settings.yaml` 里手写的注释还在
- 用 `anthropic/<模型名>` 能正常对话、调用工具
- 预计新增约 15 个测试

---

## 第 16 章 · 插件 SDK 与扩展点

### 目标

在 `liharness-plugin-api` 里定义插件 SDK，把第一阶段的接缝提升为五个扩展点；先用“内置插件”（编译在应用里的插件）验证整套机制，下一章再加载外部 jar。

### 需求

| # | 需求 | 说明 |
|---|---|---|
| 16.1 | 插件入口 | `Plugin { String id(); void start(PluginContext ctx); default void stop() {} }` |
| 16.2 | 插件上下文 | `PluginContext`：`registerTool`、`registerPromptSection`、`registerHook`、`subscribe`、`config(key)`、`dataDir()`、`onStop(AutoCloseable)`。每个 register 返回 `Registration`，可以提前撤销 |
| 16.3 | 自动撤销 | 宿主为每个插件记一本“账”，停用时先调 `stop()`，再按逆序撤销所有注册。插件作者不写反注册代码 |
| 16.4 | 工具扩展点 | 插件工具进入 `TurnTools.forTurn()`。**回合开始时取快照**：回合进行中启用 / 停用插件，不影响正在进行的回合 |
| 16.5 | 提示词扩展点 | 段落按 order 排序；内置段落占 100–400、900，插件只能用 500–800（超出范围注册失败并说明原因） |
| 16.6 | 钩子扩展点 | `SESSION_START`、`USER_PROMPT_SUBMIT`、`PRE_TOOL_USE`、`POST_TOOL_USE`、`TURN_END`。`PRE_TOOL_USE` 返回 放行 / 拒绝（原因）/ 要求审批，合并规则 拒绝 > 审批 > 放行 |
| 16.7 | 钩子的容错 | 单个钩子超时（默认 5 秒）或抛异常：按“放行”处理并记录警告；连续失败 N 次的插件自动停用 |
| 16.8 | 事件订阅 | plugin-api 里定义一套**对外的**事件类型（不直接暴露 core 的 `TurnEvent`，否则 core 改一个字段所有插件都要重新编译）。在单独的线程上分发，订阅方慢不会拖慢回合 |
| 16.9 | 技能扩展点 | 插件 jar 里的 `skills/` 目录进入 `SkillCatalog`，优先级：项目级 > 用户级 > 插件 |
| 16.10 | 内置插件 | 把一组能力（比如 `web_fetch` + 相关提示词段落）改写成内置插件，验证扩展点够用 |
| 16.11 | 架构测试 | ArchUnit：plugin-api 只依赖 JDK；core 不依赖 Spring 和 app；插件包看不到 core |
| 16.12 | API 版本 | plugin-api 有自己的版本号（`1.0`），写在 jar 的清单里；第 17 章据此判断兼容性 |

### 设计要点

- **钩子是怎么插进去的**：`PRE_TOOL_USE` 包装成一个 `ToolGate`，放在第 10 章规则链的最前面。不改 `ToolExecutor`，也不改 `AgentLoop`。这就是第 10 章把权限做成“gate 链”的原因。
- **对外事件 vs 内部事件**：内部的 `TurnEvent` 随时可以改；对外的事件类型一旦发布就要兼容。用一个转换器（`TurnEvent → PluginEvent`）隔开，这是第 04 章“防腐层”思路的反向应用。
- **注册表的线程安全**：注册表用不可变快照（`volatile` 引用 + 整体替换），回合开始时取一次，和第 07 章 `ToolSet` 的思路一致。

### 不做

- 插件之间的依赖。每个插件只和宿主打交道（[plugin-architecture.md](plugin-architecture.md) 第 5 节）。
- 插件替换内核（模型调用、循环、存储）。

### 验收

- 内置插件启用后，工具、段落、钩子、技能都生效；停用后全部消失，下一个回合模型看不到它的工具
- 一个 `PRE_TOOL_USE` 钩子拒绝 `run_command(rm *)`，模型收到原因并换一种做法
- 一个故意超时的钩子不会卡住回合
- ArchUnit 测试通过；预计新增约 25 个测试

---

## 第 17 章 · 插件宿主

### 目标

加载外部插件 jar：放进插件目录（或在界面上选择文件）→ 启用 → 立即可用；停用 → 立即撤销；替换 jar 不需要重启应用。

### 需求

| # | 需求 | 说明 |
|---|---|---|
| 17.1 | 插件目录 | `~/.liharness/plugins/*.jar`；启动时扫描 |
| 17.2 | 清单 | `META-INF/liharness-plugin.properties`：id、name、version、api-version、description；入口类用 `ServiceLoader`（`META-INF/services/com.pubbge.liharness.domain.spi.plugin.Plugin`） |
| 17.3 | 版本检查 | 主版本相同、次版本不高于宿主才加载，否则进入“失败”并说明 |
| 17.4 | 类加载隔离 | 每个插件一个 `URLClassLoader`，父加载器是平台类加载器，只有 `com.pubbge.liharness.domain.spi` 包委托给宿主 |
| 17.5 | 状态 | 已停用 / 已启用 / 失败（附原因）。**新安装的插件默认停用**，用户确认后才启用；启用状态保存在 `settings.yaml` 的 `plugins` 段 |
| 17.6 | 热替换 | 停用 → 关闭类加载器 → 替换 jar → 启用。加载前先把 jar 复制到缓存目录再加载（Windows 上被加载的 jar 文件会被锁住，不能替换） |
| 17.7 | 插件配置 | 清单里声明配置项（名字、类型、说明、是否是密钥），界面上编辑；密钥类配置和 API Key 一样存进 `.credentials.yaml`（名字带插件 id 前缀），只进不出 |
| 17.8 | 插件工作台 | 界面：插件列表、状态、启用 / 停用、配置、注册了哪些工具和钩子、最近的错误 |
| 17.9 | 示例插件 | `examples/hello-plugin`：独立 Maven 工程，只依赖 plugin-api（`provided`），提供一个工具 + 一个钩子 |
| 17.10 | 项目级 MCP 的信任 | 第 14 章没做的：工作区里的 `.liharness/mcp.json` 需要用户在界面上确认“信任这个工作区”后才生效，确认记录按工作区路径 + 文件哈希保存 |

### 设计要点

- **类加载隔离不是安全沙箱**：插件和应用权限相同。界面上安装时要明确提示“只安装你信任的插件”；不可信的能力用 MCP（独立进程）。
- **停用时的资源泄漏**：插件自己起的线程、打开的文件如果没在 `stop()` 里关，类加载器就关不掉（内存泄漏）。宿主在停用后检查：插件的类加载器是否还能被回收（弱引用 + 若干次 GC 后仍存在则在工作台显示警告）。
- **工具名冲突**：后启用的插件进入“失败”状态，不会悄悄覆盖。

### 验收

- 不重启应用，安装 `hello-plugin`、启用、被模型调用、停用、替换成新版本再启用
- 版本不兼容的插件显示原因
- 插件 `start()` 抛异常不影响应用和其他插件
- 预计新增约 20 个测试（用测试时现场编译的小 jar）

---

## 第 18 章 · 插件市场

### 目标

在界面上浏览、搜索、安装、更新、卸载插件。

### 需求

| # | 需求 | 说明 |
|---|---|---|
| 18.1 | 目录索引 | 一个 JSON 文件（地址可配置，默认指向项目仓库里的 `marketplace/index.json`）：每个插件的 id、名称、说明、作者、主页、版本列表（版本号、api-version、下载地址、SHA-256、大小） |
| 18.2 | 浏览与搜索 | 按名称 / 说明搜索；显示是否已安装、是否有更新、是否兼容当前版本 |
| 18.3 | 下载与校验 | 只允许 HTTPS；限制大小；下载到临时文件，**校验 SHA-256** 后原子移动到插件目录；校验失败删除临时文件并报错 |
| 18.4 | 安装后默认停用 | 和第 17 章一致，用户确认后启用 |
| 18.5 | 更新 | 检查已安装插件的新版本；更新 = 停用 → 替换 → 按原状态恢复 |
| 18.6 | 卸载 | 停用 → 删除 jar 和插件数据目录（数据目录删除前确认） |
| 18.7 | 离线 | 拿不到索引时显示上次缓存的索引和提示，不影响已安装插件 |

### 设计要点

- 索引和插件都是**数据**，按第一阶段的规则当作不可信输入处理：校验格式、限制大小，界面上显示的文字要转义。
- SHA-256 只保证“下载的文件就是索引里说的那个”，不保证“这个插件是安全的”。签名和可信发布者放到“以后再说”。

### 验收

- 从本地搭的静态索引安装一个插件，改坏 SHA-256 后安装失败
- 有新版本时显示“更新”，更新后启用状态不变
- 预计新增约 12 个测试（用 JDK 自带的 `HttpServer` 模拟索引和下载）

---

## 第 19 章 · 原生能力

### 目标

把桌面应用该有的本机能力补齐，并让“后端出问题”时用户依然能自助排查。

### 需求

| # | 需求 | 说明 |
|---|---|---|
| 19.1 | 登录 shell 的 PATH | macOS / Linux 上从 Dock 启动的应用拿不到终端里的 PATH，导致 `npx`、`uvx`、`mvn` 找不到（第 14 章的常见坑）。Rust 启动时运行一次登录 shell（`$SHELL -l -c 'echo $PATH'`，带超时），把结果传给 Java；Java 用它启动 `run_command` 和 MCP 服务器 |
| 19.2 | 系统通知 | 回合结束或需要审批时，如果窗口不在前台，发系统通知；点击通知回到对应会话 |
| 19.3 | 打开文件 / 链接 | 工具卡片里的文件路径可以点击，用系统默认程序或指定编辑器打开；回答里的链接用系统浏览器打开（Tauri opener 插件） |
| 19.4 | Git 状态 | 侧边栏显示当前工作区的分支和改动文件数；回合结束后刷新。Rust 侧调用 `git` 命令实现（不引入 libgit2） |
| 19.5 | 诊断面板 | 后端起不来或者崩溃时，Rust 侧仍然能显示：后端日志最后 200 行、运行记录、端口、健康检查结果，并提供“重启后端”“打开日志目录” |
| 19.6 | 窗口状态 | 记住窗口大小和位置、上次打开的会话 |

### 设计要点

- 所有本机能力都是**固定的 Tauri command**，前端不能传任意命令。capability 文件只开放需要的权限（第 02 章的原则）。
- 登录 shell 可能很慢甚至卡住（用户的 `.zshrc` 里什么都有），必须有超时，失败时退回系统默认 PATH 并在诊断里说明。

### 验收

- 从 Dock 启动的应用能启动 `npx` 类的 MCP 服务器
- 杀掉 Java 进程后，诊断面板能显示日志并一键重启后端
- 预计新增约 8 个 Rust 测试 + 若干前端测试

---

## 第 20 章 · 打包发布与评测

### 目标

产出三平台安装包（用户不需要安装 Java）；建立一套评测集，每次改动都能知道 Agent 的能力有没有退步。

### 需求：打包发布

| # | 需求 | 说明 |
|---|---|---|
| 20.1 | 精简运行时 | 用 `jdeps` 找出需要的模块，`jlink` 生成精简 JRE（目标 < 60MB） |
| 20.2 | 安装包 | Tauri bundler 把 jar 和 JRE 作为资源打进去：macOS `.dmg`、Windows `.msi`、Linux `.AppImage` / `.deb` |
| 20.3 | CI | GitHub Actions 三平台矩阵：后端测试、前端测试、Rust 测试、打包；打 tag 时发布 Release |
| 20.4 | 版本号 | 一个脚本同时修改 pom、`package.json`、`Cargo.toml`、`tauri.conf.json` 的版本号 |
| 20.5 | 签名说明 | 文档说明 macOS 公证、Windows 签名的流程（证书需要自己申请，CI 里预留步骤） |

### 需求：评测

| # | 需求 | 说明 |
|---|---|---|
| 20.6 | 评测用例 | `eval/cases/*.json`：输入、工作区夹具（一个小目录）、检查项（文件内容包含 / 命令执行成功 / 回答包含 / 最多步数） |
| 20.7 | 评测运行器 | 一个独立的 `main`，**直接 new 出 core 的类**运行回合（不启动 Spring），这正是 core 不依赖 Spring 的回报。每个用例复制一份工作区夹具，自动批准所有审批 |
| 20.8 | 指标 | 通过率、平均步数、token、耗时；输出 Markdown 报告，和上一次的结果对比 |
| 20.9 | 两种模式 | CI 里用 `ScriptedLlmAdapter` 回放（验证 Harness 本身没坏）；本地用真实模型（评估模型 + 提示词的效果） |

### 验收

- 在一台没装 Java 的机器上安装并正常使用
- 推一个 tag，Release 页面出现三平台安装包
- 评测运行器输出报告；故意改坏系统提示词，真实模型的通过率下降

**🎯 完成后：v1.0**

---

## 第三阶段（以后）：外部编排集成

第二阶段完成后，如果业务需要多代理编排，按 [ADR-0009](adr/0009-external-orchestration-via-adapters.md) 的三个位置接入，**内核不变**：

| 方式 | 做法 | 适合 |
|---|---|---|
| 模型适配器 | `SpringAiLlmAdapter implements LlmChatModelAdapter`：内部用 Spring AI 的 `ChatClient`，把它的流翻译成 `StreamChunk` | 想用 Spring AI 支持的某个模型或它的观测能力 |
| 编排作为工具 | `AdkAgentTool implements Tool`：输入任务描述，内部运行一个 Google ADK 的多代理流程，返回汇报。和第 13 章的 `task` 子代理形状一样；可以做成第 17 章的外部插件 | 某类任务需要固定的多代理协作（规划 → 执行 → 评审） |
| LiHarness 作为被编排者 | 外层的编排系统通过 HTTP 调用 LiHarness（需要一个无界面的认证方式和回合 API），把它当作“能操作本机的代理” | 团队服务器、自动化流水线 |

可能的需求：

- 无界面模式：不启动 Tauri，后端单独运行，使用配置文件里的令牌
- 回合 API 的同步版本（等回合结束再返回完整结果），方便外部系统调用
- 审批的远程处理：外部编排者通过 API 回复审批，或按预设策略自动处理
- 多代理之间的上下文传递：只传汇报（和子代理一样），还是共享一份“黑板”

这一阶段的前提是第一、二阶段的边界守住了：模型只是 `LlmChatModelAdapter`，能力只是 `Tool`，循环只有一个。
