# ADR-0011：模型端口用“拉取式流 + 细粒度片段”，领域层拼装，取消由关闭流实现

- 状态：已采纳（2026-11-26 修订：片段改成细粒度、完整消息改由领域层拼装、接口改名并移入分层包结构）
- 日期：2026-11-26

## 背景

第 04 章最初的模型接口是：

```java
ChatResponse chat(ChatRequest request, StreamListener listener, CancellationToken cancel)
```

问题：

- **接口里混进了和“聊天”无关的东西**：监听器是界面的事，取消令牌是回合的事。每个模型实现都要处理它们；
- **推送式回调难以组合**：重试层要包一层监听器、判断“有没有输出过文字”；摘要器不需要逐字输出，也得传一个空监听器。

第一次修订改成了拉取式的 `ChatStream`，但片段很粗（只有 `TextDelta` 和带完整回复的 `Completed`），**每个适配器都要自己拼工具调用、自己处理各家的不规范**；还保留了一个 `chat()` 默认方法，等于有两个生成入口。对照 deepseek-harness-java（适配器只翻译出细粒度的 `StreamChunk`，领域层的 `BlockAssembler` 统一拼装，`LlmGenerationService` 统一读流）后，决定再修订一次。完整的对比见 [设计评审：模型流式接口与包分层](../design/model-stream-and-packages.md)。

## 决定

```java
// domain.llm.adapter.port
public interface ILlmPort {
    String id();
    LlmStream stream(LlmRequest request);              // 唯一的生成入口；立即返回，错误从 next() 抛出
}
public interface LlmStream extends AutoCloseable {
    StreamChunk next() throws LlmException;            // 最后一个片段一定是 Finish
    void close();                                      // 任何线程都可以调用；之后 next() 抛 CancelledException
}

// domain.llm.model.valobj
sealed interface StreamChunk {
    TextDelta(text) · ReasoningDelta(text) · ToolCallDelta(index, id, name, argumentsDelta)
    Usage(TokenUsage) · Retry(attempt, maxAttempts, reason, delayMs) · Finish(FinishReason, model)
}
```

- **拉取式**：调用方自己决定什么时候读下一个片段，像读文件一样。（deepseek-harness-java 用 `Flow.Publisher` 推送；我们的 Agent Loop 是一个顺序推进的循环，拉取让它保持为普通的顺序代码，错误就是异常。）
- **适配器只翻译**：把各家的原始数据一对一翻译成片段，不拼装、不整理。
- **领域层拼装**：`domain.llm.service.BlockAssembler` 按 index 拼工具调用，统一处理“有工具调用就是 TOOL_USE”“截断时丢掉半截工具调用”“补 id”“丢掉没有名字的碎片”。
- **领域服务读流**：`LlmGenerationService.generate(request, cancel[, observer])` 开流、把每个片段交给拼装器和 observer、用 `cancel.onCancel(stream::close)` 把取消接到流上、保证关流。Agent Loop、模型连通性测试、摘要器都用它。端口上没有 `chat()`。
- **取消 = 关闭流**：取消令牌只出现在领域服务里，端口不认识它。
- **重试是装饰器**（`RetryingLlmPort`，领域服务）：只在还没有交出任何内容（正文、思考、工具调用）之前重试；重试前先交出一个 `Retry` 片段让界面显示进度，下一次 `next()` 再等待，等待可以被 `close()` 打断。
- OpenAI 适配器内部用读线程 + 阻塞队列把 HTTP 的推送转成拉取。

## 备选方案

| 方案 | 不选的原因 |
|---|---|
| 监听器 + 令牌参数（最初的版本） | 见背景 |
| 粗粒度片段 + 适配器自己拼（第一次修订） | 每加一个模型服务就要再写一遍拼装和整理；无法显示思考过程、重试进度 |
| `Flow.Publisher`（deepseek-harness-java）/ Reactor `Flux` | 片段在模型的线程上回调，错误要从 `onError` 绕回调用线程；取消要等下一个片段到来才生效；Reactor 会让 core 多一个依赖 |
| `Iterator<StreamChunk>` | `hasNext()` 不能抛受检异常，也没有 `close()` |

## 后果

好的：

- 加一个模型服务只需要写“翻译”，拼装规则写一次、不联网就能测（`BlockAssemblerTest`）
- 思考过程、重试进度这类新信息只需要加一种片段，调用方用 `instanceof` 选择自己关心的
- 取消的路径只有一条：关闭流，而且全项目只在 `LlmGenerationService` 里写一次

坏的：

- 片段种类多了，调用方要知道哪些是“内容”、哪些只是通知（`Retry`、`Usage`）
- 推送式的 HTTP 客户端要多一层“推转拉”（读线程 + 队列），见第 07 章
- 直接用端口（不经过 `LlmGenerationService`）时，调用方必须记得关闭流
