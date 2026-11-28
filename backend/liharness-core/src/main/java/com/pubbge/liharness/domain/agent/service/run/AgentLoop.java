package com.pubbge.liharness.domain.agent.service.run;

import com.pubbge.liharness.domain.agent.contract.outbound.ITurnEventListener;
import com.pubbge.liharness.domain.agent.model.valobj.AgentOptions;
import com.pubbge.liharness.domain.agent.model.valobj.TurnEvent;
import com.pubbge.liharness.domain.agent.model.valobj.TurnOutcome;
import com.pubbge.liharness.domain.agent.model.valobj.TurnRequest;
import com.pubbge.liharness.domain.agent.model.valobj.TurnResult;
import com.pubbge.liharness.domain.llm.error.LlmException;
import com.pubbge.liharness.domain.llm.exchange.LlmRequest;
import com.pubbge.liharness.domain.llm.exchange.LlmResponse;
import com.pubbge.liharness.domain.llm.exchange.TokenUsage;
import com.pubbge.liharness.domain.llm.message.Message;
import com.pubbge.liharness.domain.llm.message.Role;
import com.pubbge.liharness.domain.llm.service.LlmGenerationService;
import com.pubbge.liharness.domain.llm.stream.StreamChunk;
import com.pubbge.liharness.domain.llm.tool.ToolCall;
import com.pubbge.liharness.domain.shared.cancel.CancellationToken;
import com.pubbge.liharness.domain.shared.cancel.CancelledException;
import com.pubbge.liharness.domain.tool.model.valobj.ToolCallScope;
import com.pubbge.liharness.domain.tool.model.valobj.ToolOutcome;
import com.pubbge.liharness.domain.tool.service.ToolExecutor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Agent Loop：问模型 → 执行它要的工具 → 把结果告诉它 → 再问，直到它给出最终回答。
 *
 * <pre>
 *            ┌──────────────────────────────────────────┐
 *  用户输入 ─►│ 调用模型 ──finishReason──┬─ END_TURN ──────► 结束（COMPLETED）
 *            │    ▲                   ├─ MAX_TOKENS ────► 结束（MAX_TOKENS）
 *            │    │                   └─ TOOL_USE
 *            │    │                         │
 *            │    └──── 回填工具结果 ◄── 逐个执行工具
 *            └──────────────────────────────────────────┘
 *              步数达到上限 ► 结束（MAX_STEPS）；取消 ► 结束（CANCELLED）；出错 ► 结束（FAILED）
 * </pre>
 *
 * <p>它是一个普通的 Java 对象：没有 Spring、没有全局状态、不持有对话历史。所有输入在 {@link TurnRequest}
 * 里，所有输出通过 {@link ITurnEventListener} 和返回值给出。所以它可以被单元测试直接 new 出来，
 * 可以被子代理嵌套调用（第 13 章），以后也可以被外部编排框架包装成一个 Agent。
 *
 * <p>线程安全：无状态，同一个实例可以同时跑多个回合。
 */
public final class AgentLoop {

    private static final System.Logger log = System.getLogger(AgentLoop.class.getName());

    private final ToolExecutor toolExecutor;
    private final AgentOptions options;

    public AgentLoop(ToolExecutor toolExecutor, AgentOptions options) {
        this.toolExecutor = toolExecutor;
        this.options = options;
    }

    /**
     * 跑一个回合。<b>不抛异常</b>：任何结局（包括取消和出错）都体现在返回值里，并且最后一定会发出一个
     * {@link TurnEvent.TurnFinished} 事件。
     */
    public TurnResult run(TurnRequest request, ITurnEventListener listener, CancellationToken cancel) {
        Run run = new Run(request, listener, cancel);
        return run.execute();
    }

    /** 一次回合的可变状态。每个回合一个实例，所以 AgentLoop 本身可以保持无状态 */
    private final class Run {

        private final TurnRequest request;
        private final ITurnEventListener listener;
        private final CancellationToken cancel;
        private final ToolCallScope scope;

        /** 本回合新增的消息，最后整体追加到会话历史 */
        private final List<Message> added = new ArrayList<>();
        private TokenUsage usage = TokenUsage.ZERO;
        private int steps;
        private String lastText = "";

        Run(TurnRequest request, ITurnEventListener listener, CancellationToken cancel) {
            this.request = request;
            this.listener = listener;
            this.cancel = cancel;
            this.scope = new ToolCallScope(request.sessionId(), request.turnId(), request.workspace(), cancel,
                (callId, msg) -> emit(new TurnEvent.ToolProgress(callId, msg)));
        }

        TurnResult execute() {
            added.add(Message.user(request.userInput()));
            emit(new TurnEvent.TurnStarted(request.sessionId(), request.turnId(), request.model().id()));
            try {
                return loop();
            } catch (CancelledException e) {
                return finish(TurnOutcome.CANCELLED, null, null);
            } catch (LlmException e) {
                return finish(TurnOutcome.FAILED, e.getMessage(), e.kind());
            } catch (RuntimeException e) {
                // 兜底：理论上不该发生（工具异常已被 ToolExecutor 吞掉），发生了就是 bug
                log.log(System.Logger.Level.ERROR, "回合异常 turnId=" + request.turnId(), e);
                return finish(TurnOutcome.FAILED, "内部错误：" + e.getMessage(), null);
            }
        }

        private TurnResult loop() throws LlmException {
            while (true) {
                if (steps >= options.maxSteps()) {
                    return finish(TurnOutcome.MAX_STEPS,
                        "已达到单回合最大步数 " + options.maxSteps() + "，回合被中止。可以回复“继续”让它接着做。", null);
                }
                cancel.throwIfCancelled();
                steps++;

                LlmResponse response = callModel(buildRequest());
                usage = usage.plus(response.usage());
                lastText = response.text();
                // 不变量 1：模型的回复原样放回历史（包括 toolCalls）。什么都没说的空回复不记
                if (!response.text().isEmpty() || response.hasToolCalls()) {
                    added.add(response.toAssistantMessage());
                }

                switch (response.finishReason()) {
                    case END_TURN -> {
                        return finish(TurnOutcome.COMPLETED, null, null);
                    }
                    case MAX_TOKENS -> {
                        return finish(TurnOutcome.MAX_TOKENS,
                            "回答达到输出长度上限被截断了。可以回复“继续”让它接着写。", null);
                    }
                    case TOOL_USE -> runTools(response.toolCalls());
                }
            }
        }

        private LlmRequest buildRequest() {
            List<Message> messages = new ArrayList<>(request.history().size() + added.size());
            messages.addAll(request.history());
            messages.addAll(added);
            return new LlmRequest(request.systemPrompt(), messages, request.tools().specs(), request.maxOutputTokens());
        }

        /** 不变量 2：每个工具调用都要有一条结果。逐个执行，结果按调用顺序回填 */
        private void runTools(List<ToolCall> calls) {
            for (ToolCall call : calls) {
                emit(new TurnEvent.ToolCallStarted(call));
                ToolOutcome outcome = toolExecutor.execute(call, request.tools(), scope);
                added.add(outcome.toMessage());
                emit(new TurnEvent.ToolCallFinished(call, outcome.result(), outcome.duration().toMillis()));
            }
        }

        /**
         * 调用一次模型：正文片段转成 TextDelta 事件推给界面，返回拼好的完整回复。
         *
         * <p>读流、拼装、关流、把取消接到流上，都由领域服务 {@link LlmGenerationService} 负责；
         * Agent Loop 只决定“哪些片段要告诉界面”。用户点停止 → 令牌取消 → 流被关闭 →
         * 阻塞中的读取立刻抛 CancelledException → 回合以 CANCELLED 结束。
         */
        private LlmResponse callModel(LlmRequest llmRequest) throws LlmException {
            return new LlmGenerationService(request.model()).generate(llmRequest, cancel, chunk -> {
                if (chunk instanceof StreamChunk.TextDelta delta) {
                    emit(new TurnEvent.TextDelta(delta.text()));
                }
            });
        }

        private TurnResult finish(TurnOutcome outcome, String error, LlmException.Kind kind) {
            closeDanglingToolCalls();
            TurnResult result = new TurnResult(request.turnId(), outcome, lastText, added, usage, steps, error, kind);
            emit(new TurnEvent.TurnFinished(result));
            return result;
        }

        /**
         * 回合中途结束（取消、出错）时，最后一条助手消息里可能有还没执行的工具调用。
         * 给它们补上“未执行”的结果，保证存进历史的消息满足不变量 2，否则下一个回合会被模型服务拒绝。
         */
        private void closeDanglingToolCalls() {
            int lastAssistant = -1;
            for (int i = added.size() - 1; i >= 0; i--) {
                if (added.get(i).role() == Role.ASSISTANT) {
                    lastAssistant = i;
                    break;
                }
            }
            if (lastAssistant < 0 || !added.get(lastAssistant).hasToolCalls()) {
                return;
            }
            Set<String> answered = new HashSet<>();
            for (int i = lastAssistant + 1; i < added.size(); i++) {
                answered.add(added.get(i).toolCallId());
            }
            for (ToolCall call : added.get(lastAssistant).toolCalls()) {
                if (!answered.contains(call.id())) {
                    added.add(Message.toolError(call.id(), "未执行：回合在执行这个工具之前被中止了"));
                }
            }
        }

        private void emit(TurnEvent event) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException e) {
                // 监听方的问题（比如前端断开导致推送失败）不能影响回合本身
                log.log(System.Logger.Level.WARNING, "事件监听方异常：" + e);
            }
        }
    }
}
