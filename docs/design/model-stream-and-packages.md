# 设计评审：模型流式接口 与 包分层

> 状态：**已决定**（2026-11-26）：D1–D7 全部按建议执行，第 04–13 章的代码和教程已按结论重做，结论写成了 [ADR-0011](../adr/0011-pull-based-model-stream.md)（修订版）。实施时和第 8 节相比有几处调整，见文末“第四部分”。
>
> 参考来源：
> - 本地的 **deepseek-harness-java**（下文简称 dsh），源码逐个看过；
> - **Claude Code**：源码不公开，只能参考 Anthropic 的公开文档和官方 SDK（Claude Agent SDK、Anthropic Java SDK）；
> - Java 生态里另外两个主流库 **LangChain4j**、**Spring AI** 的公开接口，用来对照。

---

## 第一部分：模型流式接口

### 1. 先说清楚“流式接口”要解决哪几件事

无论哪种设计，模型接口都要回答这五个问题：

| # | 问题 | 为什么重要 |
|---|---|---|
| 1 | **谁驱动**：模型把片段“推”给我（push），还是我向模型“拉”下一个片段（pull） | 决定线程模型和代码写法 |
| 2 | **怎么停**：用户点停止时，怎么让 HTTP 连接立刻断开 | 不停就是白花钱，还会卡住界面 |
| 3 | **错误怎么传**：网络断、限流、上下文太长，从哪里报出来 | Agent Loop 要按错误类型决定重试 / 压缩 / 失败 |
| 4 | **片段有多细**：只有文字，还是区分思考过程、工具调用片段、用量、结束原因 | 决定界面能显示什么（比如 DeepSeek reasoner 的思考过程） |
| 5 | **谁来拼完整消息**：每个适配器自己拼，还是领域层统一拼 | 决定加一个新供应商要写多少代码 |

### 2. 四种参考设计

#### 设计 A：回调（LangChain4j，以及我们最早的第一版）

```java
// LangChain4j
public interface StreamingChatModel {
    void chat(ChatRequest request, StreamingChatResponseHandler handler);
}
public interface StreamingChatResponseHandler {
    default void onPartialResponse(String partialResponse) {}
    void onCompleteResponse(ChatResponse completeResponse);
    void onError(Throwable error);
}
```

- 驱动：**推**。`chat()` 调用后立刻返回，回调发生在后台线程。
- 停止：较新的版本在回调参数里给一个 `StreamingHandle`，在回调里调用它的取消方法。
- 我们第一版的 `chat(request, listener, cancel)` 就是这一类，你指出的问题也正是它的典型问题：**接口参数里混进了“界面怎么显示”“回合怎么取消”**。

#### 设计 B：响应式发布者（dsh、Spring AI）

dsh 的模型端口（`domain/llm/adapter/LlmAdapter.java`）：

```java
public interface LlmAdapter {
    Flow.Publisher<StreamChunk> stream(GenerateOptions options);   // 唯一的生成入口
    ...
}
```

dsh 的片段类型（`domain/llm/model/valobj/StreamChunk.java`）：

```java
public sealed interface StreamChunk permits BlockStart, TextDelta, ReasoningDelta,
        ToolCallDelta, BlockEnd, Usage, Finish, Retry { ... }
```

一次性拿结果，用领域服务在流上拼（`domain/llm/service/LlmGenerationService.java`）：

```java
public CompletableFuture<Message> generate(GenerateOptions options) {
    var assembler = new BlockAssembler();
    llmRuntimePort.stream(options).subscribe(/* onNext → assembler.push(chunk) */);
    ... // 等待完成后 assembler.message(...)
}
```

Agent 循环里怎么用（`domain/agent/service/run/ReactLoopAgent.java`，第 569–655 行，节选）：

```java
Flow.Publisher<StreamChunk> publisher = llm.stream(generateOptions);
publisher.subscribe(new Flow.Subscriber<>() {
    public void onNext(StreamChunk chunk) {
        if (running.abort().get()) { aborted.set(true); subscription.cancel(); return; }
        assembler.push(chunk);
        if (chunk instanceof StreamChunk.TextDelta td) { streamDeltaSink.accept(td.text()); }
        ...
    }
    public void onError(Throwable t)  { streamError.set(t); done.countDown(); }
    public void onComplete()          { done.countDown(); }
});
// 等待流结束，同时每 100ms 检查一次是否被取消
while (!done.await(100, TimeUnit.MILLISECONDS)) {
    if (running.abort().get()) { aborted.set(true); break; }
}
```

OpenAI / DeepSeek 适配器内部用 JDK 的 `SubmissionPublisher` 发布片段（`infrastructure/adapter/llm/deepseek/DeepSeekAdapter.java`）。

Spring AI 是同一类，只是用 Reactor：

```java
public interface StreamingChatModel { Flux<ChatResponse> stream(Prompt prompt); }
public interface ChatModel          { ChatResponse call(Prompt prompt); }   // 同步一次性
```

- 驱动：**推**。
- 停止：`subscription.cancel()`（Reactor 是 `Disposable.dispose()`）。
- 错误：`onError(Throwable)`；dsh 还把模型返回的错误放进片段里（`Finish(FinishReason.Error)`）。
- 片段：dsh 很细（8 种），这是它最值得学的地方。
- 拼装：dsh 在**领域层**用 `BlockAssembler` 统一拼，适配器只吐原始片段。

#### 设计 C：可关闭的拉取式流（Anthropic Java SDK、Claude Agent SDK，以及我们现在的设计）

Anthropic 官方 Java SDK：

```java
public interface StreamResponse<T> extends Closeable {
    Stream<T> stream();   // 拉：for-each 一个个取事件
    void close();         // 关闭底层连接
}

try (StreamResponse<RawMessageStreamEvent> resp = client.messages().createStreaming(params)) {
    resp.stream().forEach(accumulator::accumulate);   // 要完整消息时，用 MessageAccumulator 在流上拼
}
Message message = accumulator.message();
```

Claude Code 的 Agent SDK（TypeScript）：`query()` 返回一个异步生成器，调用方 `for await` 一条条取；中途停止调用返回对象上的 `interrupt()`。也就是说，**流和“停止”是同一个对象**。

我们现在的接口：

```java
public interface ChatModel {
    ChatStream stream(ChatRequest request);                       // 立即返回
    default ChatResponse chat(ChatRequest r) throws ModelException { ... }   // = 读完整个流
}
public interface ChatStream extends AutoCloseable {
    ChatEvent next() throws ModelException;   // 阻塞拿下一个：TextDelta … 最后 Completed(response)
    void close();                             // 任何线程可调用；之后 next() 抛 CancelledException
}
```

Agent 循环里：

```java
ChatStream stream = model.stream(request);
CancellationToken.Registration r = cancel.onCancel(stream::close);   // 停止 = 关流
try {
    while (true) {
        ChatEvent e = stream.next();
        if (e instanceof ChatEvent.TextDelta d) listener.onEvent(new TurnEvent.TextDelta(d.text()));
        if (e instanceof ChatEvent.Completed c) return c.response();
    }
} finally { r.close(); stream.close(); }
```

- 驱动：**拉**。
- 停止：`close()`，阻塞中的 `next()` 立刻醒来。
- 错误：`next()` 直接抛 `ModelException`（带错误分类），Agent 循环用普通的 try/catch 处理。
- 片段：**目前太粗**，只有 `TextDelta` 和 `Completed`。
- 拼装：**目前放错了地方**，OpenAI 适配器在内部自己拼（`OpenAiStreamAccumulator`）。

#### 设计 D：只有一次性调用（不流式）

```java
ChatResponse chat(ChatRequest request);
```

最简单，但界面只能等几秒到几十秒后一次性显示整段回答，也没法中途停止。现在的主流 Harness 都不这么做，这里只作为对照。

### 3. 对比

| | A 回调 | B 发布者（dsh / Spring AI） | C 拉取式流（Anthropic SDK / Claude Agent SDK / 现在） | D 一次性 |
|---|---|---|---|---|
| 驱动 | 推 | 推 | 拉 | — |
| 接口里有没有界面 / 取消参数 | **有** | 没有 | 没有 | 没有 |
| 停止 | 回调里拿 handle 取消 | `subscription.cancel()` | `close()` | 不能 |
| 停止后多久生效 | 下一个回调时 | 下一个片段到达时；dsh 的循环靠 **100ms 轮询**发现 | **立即**（阻塞的 `next()` 被唤醒） | — |
| 错误怎么到 Agent 循环 | `onError` 回调，要自己存下来再抛 | `onError` 回调，要自己存下来再抛 | `next()` 直接抛，普通 try/catch | 直接抛 |
| Agent 循环的代码形状 | 回调 + 等待 | 订阅者 + CountDownLatch + 轮询 | 普通 while 循环 | 一行 |
| 线程 | 适配器的后台线程调你 | 发布者的线程调你 | 你自己的线程读；适配器内部要一个读线程“推转拉” | 你自己的线程 |
| 重试包装（第 07 章） | 要包一层 handler | 要写一个 Processor | 包一层 `ChatStream`，较直观 | 简单 |
| 写测试 | 要等异步回调 | 要等异步完成 | 同步代码，直接断言 | 最简单 |
| 新依赖 | 无 | 无（JDK Flow）/ Reactor（Spring AI） | 无 | 无 |
| 背压 | 无 | 有（`request(n)`），但 dsh 直接 `request(Long.MAX_VALUE)` 没用上 | 天然有：不 `next()` 就不读 | — |

### 4. 片段粒度和“谁来拼”：dsh 做得比我们好的地方

| | dsh | 我们现在 |
|---|---|---|
| 文字片段 | `TextDelta(index, text)` | `TextDelta(text)` |
| 思考过程 | `ReasoningDelta`（DeepSeek reasoner 的 `reasoning_content`） | **没有**，思考过程丢掉了 |
| 工具调用片段 | `ToolCallDelta(index, id, name, argumentsDelta)` | 没有，只在最后的完整回复里 |
| 用量 | `Usage` 片段 | 在最后的完整回复里 |
| 结束原因 | `Finish(reason)`，错误也是一种结束原因 | 在最后的完整回复里；错误走异常 |
| 重试提示 | `Retry(attempt, max, message)`，界面能显示“正在重试 2/3” | **没有** |
| 拼成完整消息 | 领域层 `BlockAssembler`，所有供应商共用 | 每个适配器自己拼 |

Anthropic Java SDK 也是同样的思路：流里是原始事件，要完整消息时用 `MessageAccumulator` 在流上拼。

### 5. 给 LiHarness 的三个候选方案

**方案 1（推荐）：拉取式 + dsh 的细粒度片段 + 领域层统一拼装**

```java
// 领域层端口：只有一个生成入口
public interface ILlmPort {
    LlmStream stream(LlmRequest request);
}
public interface LlmStream extends AutoCloseable {
    StreamChunk next() throws LlmException;   // 流结束返回 null（或 Finish 之后不再调用）
    void close();
}
// 片段：照搬 dsh 的划分
public sealed interface StreamChunk permits TextDelta, ReasoningDelta, ToolCallDelta, Usage, Finish, Retry { ... }

// 领域服务：一次性拿完整消息（摘要、子代理汇报这些地方用）
public final class LlmGenerationService {
    public Message generate(LlmRequest r, CancellationToken cancel) throws LlmException { ... BlockAssembler ... }
}
```

- 保留拉取式的优点：停止立即生效、不用轮询、Agent 循环是普通的 while 循环、测试简单；
- 学 dsh 的优点：片段细（能显示思考过程和重试）、适配器只吐原始片段、`BlockAssembler` 在领域层；
- 和 Anthropic 官方 Java SDK、Claude Agent SDK 的形状一致（可迭代 + 可关闭）。

**方案 2：完全照搬 dsh，用 `Flow.Publisher<StreamChunk>`**

```java
public interface ILlmPort { Flow.Publisher<StreamChunk> stream(LlmRequest request); }
```

- 优点：和 dsh 一模一样，对照着读最方便；JDK 自带，不加依赖；
- 缺点：Agent 循环要写订阅者 + 等待 + 轮询取消（见上面的 dsh 代码），错误要先存起来再抛；测试要等异步完成。

**方案 3：保持现状（拉取式，粗粒度片段，适配器自己拼）**

- 改动最小，但第 4 节列出的问题都还在。

---

## 第二部分：包分层

### 6. Claude Code 能参考什么

Claude Code 是 TypeScript 写的，**源码不公开**，公开文档里也没有它内部的包结构，所以包分层没法参考它。下面完全按 dsh 来。

### 7. dsh 的包分层规则（从源码里归纳）

根包下面按**层**分，层下面按**领域**分，领域下面按**职责**分：

```text
<根包>
├── domain.<领域>                     领域层（纯规则，不碰 IO）
│   ├── adapter.port                 端口接口：IFsPort、ISkillProviderPort、ILlmRuntimePort …（I 前缀）
│   ├── adapter.repository           仓储接口：ISessionEventStore …（I 前缀）
│   ├── model.entity                 实体：有 id、有生命周期（AgentOptions、Phase …）
│   ├── model.valobj                 值对象：不可变 record（StreamChunk、GenerateOptions、TokenUsage …）
│   ├── contract.inbound / outbound  对外约定（agent 领域：AgentEventListener、RuntimeApprovalGateway）
│   └── service[.子功能]              领域服务：ReactLoopAgent（service.run）、BasicCompactionEngine（service.compaction）…
├── domain.tool.<工具族>              工具本身也在领域层：domain.tool.fs（FsReadTool…）+ domain.tool.fs.adapter.port（IFsPort）
├── domain.spi                        插件契约（单独的 types 模块里）：PluginContext、PluginHook …
├── infrastructure.adapter.<领域>     端口的实现：adapter.llm.openai、adapter.llm.deepseek、adapter.fs（LocalFsService）、
│                                     adapter.shell（LocalShellExecutor）、adapter.mcp（StdioMcpClient）、adapter.repository …
├── infrastructure.config / dao / gateway
├── cases.<用例>                      用例编排：cases.agent、cases.session、cases.approval …（复杂流程再分 factory + node）
├── trigger.http / filter / service.stream   入口：Controller、认证过滤器、SSE
└── app                               Spring Boot 启动
```

几条关键规则：
- **端口在领域层，实现在 infrastructure**。比如文件工具 `FsReadTool` 在 `domain.tool.fs`，它只调用 `IFsPort`；真正读磁盘的 `LocalFsService` 在 `infrastructure.adapter.fs`。
- **接口用 `I` 前缀**（端口、仓储），值对象放 `model.valobj`，实体放 `model.entity`。
- 子代理工具在 `domain.agent.service.subagent`，MCP 工具在 `domain.tool.mcp`，MCP 客户端实现在 `infrastructure.adapter.mcp`。

### 8. LiHarness 按这个规则的新包结构

模块不变（`liharness-plugin-api` / `liharness-core` / `liharness-app`），只改包。core 里放 `domain` 和 `infrastructure` 两层，app 里放 `cases`、`trigger`、`app`。层之间的依赖用 ArchUnit 测试守住（见第 9 节）。

```text
liharness-plugin-api
└── com.pubbge.liharness.domain.spi.tool          Tool、ToolArgs、ToolContext、ToolResult、ToolException

liharness-core
├── com.pubbge.liharness.domain
│   ├── shared.model.valobj                       CancellationToken、CancelledException（各领域共用）
│   ├── llm
│   │   ├── adapter.port                          ILlmPort、LlmStream
│   │   ├── model.valobj                          LlmRequest、Message、Role、ToolCall、ToolSpec、TokenUsage、
│   │   │                                         FinishReason、StreamChunk、LlmFailure/LlmException、ModelProfile
│   │   └── service                               LlmGenerationService、BlockAssembler、RetryingLlmPort
│   ├── agent
│   │   ├── contract.outbound                     ITurnEventListener（原 TurnListener）
│   │   ├── model.entity                          AgentOptions
│   │   ├── model.valobj                          TurnRequest、TurnResult、TurnOutcome、TurnEvent
│   │   ├── service.run                           AgentLoop
│   │   └── service.subagent                      TaskTool、SubagentSettings
│   ├── tool
│   │   ├── model.valobj                          ToolOutcome、ToolCallScope
│   │   ├── service                               ToolExecutor、ToolRegistry（原 ToolSet）、SchemaValidator、ToolOutputLimiter
│   │   ├── fs / fs.adapter.port                  ReadFileTool、WriteFileTool、EditFileTool… / IFsPort
│   │   ├── shell / shell.adapter.port            RunCommandTool、DangerousCommandGuard / IShellExecutor
│   │   ├── web / web.adapter.port                WebFetchTool / IWebFetchPort
│   │   ├── time、todo                            GetTimeTool、TodoWriteTool
│   │   ├── skill                                 SkillTool
│   │   └── mcp / mcp.adapter.port                McpTool / IMcpClientPort
│   ├── permission
│   │   ├── adapter.port                          IApprovalGateway（原 ApprovalBroker）
│   │   ├── model.valobj                          PermissionMode、PermissionRule、PermissionDecision、ApprovalRequest、ApprovalReply…
│   │   └── service                               PermissionPolicy、IToolGate、RuleGate、ModeGate、SessionPermissions
│   ├── session
│   │   ├── adapter.repository                    ISessionRepository（原 SessionStore）
│   │   ├── model.entity / model.valobj           SessionMeta / SessionContext
│   │   └── service                               HistoryRepair
│   ├── context
│   │   ├── adapter.port                          IProjectInstructionsPort（读 LIHARNESS.md）
│   │   ├── model.valobj                          ContextWindow、Conversation、Compaction、PromptSection
│   │   └── service.compaction / token / prompt   Compactor、Summarizer、ToolResultClearer / TokenEstimator / SystemPromptBuilder
│   └── skill
│       ├── adapter.port                          ISkillProviderPort
│       ├── model.entity                          Skill
│       └── service                               SkillCatalog
└── com.pubbge.liharness.infrastructure
    ├── adapter.llm                               LlmAdapterFactory
    ├── adapter.llm.openai / adapter.llm.mock     OpenAiCompatibleAdapter… / MockLlmAdapter、ScriptedLlmAdapter
    ├── adapter.fs / adapter.shell / adapter.web  LocalFsAdapter（含工作区沙箱）/ LocalShellExecutor / JdkWebFetchAdapter
    ├── adapter.repository                        JsonlSessionRepository、InMemorySessionRepository
    ├── adapter.skill                             FileSystemSkillProvider（含 SKILL.md 解析）
    ├── adapter.context                           FileProjectInstructions
    ├── adapter.mcp                               StdioMcpClient、JsonRpcChannel、McpManager、McpConfigFile
    └── support                                   Json、ChildProcesses

liharness-app
├── com.pubbge.liharness.cases
│   ├── agent                                     TurnCase（原 TurnService）、TurnTools、TurnRegistry、SystemPrompts、ContextService
│   ├── approval                                  ApprovalCase（原 ApprovalService）
│   ├── session / settings / skill / mcp / diagnostics
├── com.pubbge.liharness.infrastructure.adapter.settings   SettingsRepository（settings.json）、SecretStore
├── com.pubbge.liharness.trigger
│   ├── http                                      AgentStreamController、ApprovalController、SessionController、
│   │                                             SettingsController、McpController、DiagnosticsController、SystemController
│   ├── filter                                    TokenAuthFilter、TokenVerifier（原 app.security）
│   └── service.stream                            SseTurnListener、LoggingTurnListener
└── com.pubbge.liharness.app
    ├── LiHarnessApplication
    ├── config                                    LlmConfig、ToolConfig、AgentConfig、PermissionConfig、SessionConfig、McpConfig、LiHarnessProperties
    └── lifecycle                                 ParentWatchdog、ShutdownCoordinator
```

### 9. 依赖规则（写成测试，第 04、05 章引入）

```text
trigger → cases → domain ← infrastructure
app 负责把它们装配起来
```

- `domain` 不能依赖 `infrastructure`、`cases`、`trigger`，**也不能依赖 Spring**（这一点比 dsh 严：dsh 的 domain 依赖了 spring-context）；
- `cases` 不能依赖 `trigger`；
- `infrastructure` 只能实现 `domain` 的端口，不能调用 `cases`；
- `domain.spi` 只依赖 JDK。

### 10. 这次改动会带来的变化

| 变化 | 影响 |
|---|---|
| 文件、命令、网页工具改成“工具在领域层 + 端口 + 基础设施实现” | 第 06 章要多写 3 个端口和 3 个实现，但工具本身可以用假端口测试，不碰真磁盘 |
| `ChatModel` → `ILlmPort`，`ChatEvent` → `StreamChunk` | 第 04、05、07、11、12 章的代码和讲解要重写 |
| `SessionStore` → `ISessionRepository` | 第 10 章 |
| app 拆成 `cases` / `trigger` / `app` | 第 03 章起所有 Controller 的包都变；第 01 章的包结构说明要改 |
| 你仓库里已经写好的 ch01–ch04 代码 | 包名要跟着改（我会给出迁移清单，或直接帮你改好工作区，不提交） |

---

## 第三部分：需要你决定的事

| # | 问题 | 选项 | 我的建议 |
|---|---|---|---|
| D1 | 流式接口的驱动方式 | 方案 1 拉取式 / 方案 2 `Flow.Publisher`（同 dsh）/ 方案 3 保持现状 | 方案 1 |
| D2 | 片段粒度 | 照搬 dsh 的细粒度（文字、思考、工具调用、用量、结束、重试）/ 保持粗粒度 | 细粒度 |
| D3 | 完整消息由谁拼 | 领域层 `BlockAssembler` / 适配器自己拼 | 领域层 |
| D4 | 接口命名 | 端口、仓储用 `I` 前缀（同 dsh）/ 不加前缀 | 同 dsh，加 `I` |
| D5 | 文件、命令、网页工具是否走端口 | 走端口（同 dsh）/ 工具直接做 IO | 走端口 |
| D6 | 插件契约的包名 | `domain.spi.tool`（同 dsh）/ `api.tool`（现在） | 同 dsh |
| D7 | 第 8 节的包结构 | 照此执行 / 你标出要改的地方 | — |

结论：全部按“我的建议”一栏执行。

---

## 第四部分：实施时的调整

第 8 节是动手之前的草图。逐章实现时，下面几处做了调整，原因写在右边。以代码和 [架构设计](../architecture.md) 第 4 节为准。

| 草图 | 实际 | 原因 |
|---|---|---|
| `domain.shared.model.valobj` 放取消令牌 | `domain.shared.cancel`、`domain.shared.json` | 取消令牌有行为（注册回调、取消），不是值对象；`Json` 领域层和基础设施都要用，放 `infrastructure.support` 会让领域层依赖基础设施 |
| `ToolRegistry`（原 `ToolSet`） | 保留 `ToolSet`，放 `domain.tool.model.valobj` | 它是“一个回合的工具集合”，不可变、每个回合组装一次，是值对象，不是注册表 |
| `AgentOptions` 在 `model.entity` | `model.valobj` | 没有身份、不可变 |
| `IToolGate` | `ToolGate`，在 `domain.permission.service` | 它的实现全部是领域规则，不需要外部提供。`I` 前缀只给“由外部实现、装配注入”的端口和仓储 |
| `DangerousCommandGuard` 在 `tool.shell` | `DangerousCommandGate`，在 `domain.permission.service` | 它是权限的一个规则来源，和其他 gate 一起汇总 |
| 用 ArchUnit 检查分层 | 两个扫 import 语句的测试：core 的 `ArchitectureTest`（第 04 章）、app 的 `AppArchitectureTest`（第 05 章） | 规则只有几条，不想为此加一个依赖；原理也更直观。规则多起来再换 |
| `infrastructure.adapter.settings` 放在 app 模块 | 领域：`domain.settings`（`Settings`、`ISettingsRepository`）；实现：core 的 `infrastructure.adapter.repository.FileSettingsRepository`；`SecretStore` 在 `cases.settings` | 设置的读写是纯 Java，不需要 Spring，放 core 才能和其他仓储一样单独测试；密钥只在内存里，不是持久化，属于用例层的状态 |
| 模型工厂 | 新增端口 `ILlmPortFactory`（`domain.llm.adapter.port`），实现 `LlmAdapterFactory` | 用例 `ModelCase` 要按设置切换模型，但不能 import 基础设施 |
| `ISkillProviderPort` + `FileSystemSkillProvider`（含 SKILL.md 解析） | `ISkillFilePort` + `LocalSkillFileAdapter`；`SkillParser` 在 `domain.skill.service` | SKILL.md 的格式、名字规则、覆盖顺序都是规则，应该在领域层；端口只负责列目录、读文本、解析符号链接，`skill` 工具读技能目录里的文件也走它 |
| `McpManager` 在 `infrastructure.adapter.mcp` | `McpServerRegistry` 在 `domain.tool.mcp.service`；新增 `IMcpClientFactory`、`IMcpConfigRepository`；值对象在 `domain.tool.mcp.model.valobj` | 服务器的状态机、代数、重名过滤是规则，和连接方式无关；这样用例和 Controller 也不必 import 基础设施，`McpServerRegistryTest` 不起进程就能测 |
| `Json`、`ChildProcesses` 在 `infrastructure.support` | 只有 `ChildProcesses` 在 `infrastructure.support.process` | 见第一行 |
| — | 新增 `StreamChunk.Retry` | 重试最长要等 30 秒，界面需要知道“正在重试” |
| — | 新增用例 `SessionCase` | 删除会话要先确认没有回合在跑、再清掉内存里的读文件记录和会话授权，这是业务流程 |
| `LiHarnessApplication` 在 `app` | 根包 `com.pubbge.liharness` | Spring 只扫描启动类所在的包及子包，放在 `app` 下扫不到 `trigger`、`cases` |
| `diagnostics` 用例 | 没有，`DiagnosticsController` 直接读领域接口 | 只是把几个状态拼成一份文本，没有流程可编排 |
