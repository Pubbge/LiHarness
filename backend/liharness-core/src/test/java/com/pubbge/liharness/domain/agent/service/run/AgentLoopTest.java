package com.pubbge.liharness.domain.agent.service.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pubbge.liharness.domain.agent.model.valobj.AgentOptions;
import com.pubbge.liharness.domain.agent.model.valobj.TurnEvent;
import com.pubbge.liharness.domain.agent.model.valobj.TurnOutcome;
import com.pubbge.liharness.domain.agent.model.valobj.TurnRequest;
import com.pubbge.liharness.domain.agent.model.valobj.TurnResult;
import com.pubbge.liharness.domain.llm.error.LlmException;
import com.pubbge.liharness.domain.llm.exchange.LlmRequest;
import com.pubbge.liharness.domain.llm.message.Message;
import com.pubbge.liharness.domain.llm.message.Role;
import com.pubbge.liharness.domain.llm.tool.ToolCall;
import com.pubbge.liharness.domain.shared.cancel.CancellationToken;
import com.pubbge.liharness.domain.spi.tool.Tool;
import com.pubbge.liharness.domain.spi.tool.ToolArgs;
import com.pubbge.liharness.domain.spi.tool.ToolContext;
import com.pubbge.liharness.domain.spi.tool.ToolResult;
import com.pubbge.liharness.domain.tool.model.valobj.ToolSet;
import com.pubbge.liharness.domain.tool.service.ToolExecutor;
import com.pubbge.liharness.infrastructure.adapter.llm.mock.ScriptedLlmAdapter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentLoopTest {

    /** 一个会回显参数的工具 */
    private static final Tool ECHO = new SimpleTool("echo", (args, ctx) -> ToolResult.ok("echo:" + args.string("text")));

    private final AgentLoop loop = new AgentLoop(new ToolExecutor(), new AgentOptions(5));
    private final List<TurnEvent> events = new ArrayList<>();

    private TurnResult run(ScriptedLlmAdapter model, ToolSet tools, CancellationToken cancel) {
        return run(model, tools, cancel, List.of());
    }

    private TurnResult run(ScriptedLlmAdapter model, ToolSet tools, CancellationToken cancel, List<Message> history) {
        TurnRequest request = new TurnRequest("s1", "t1", model, "系统提示", history, "你好", tools, null, 0);
        return loop.run(request, events::add, cancel);
    }

    @Test
    void plainAnswer() {
        ScriptedLlmAdapter model = new ScriptedLlmAdapter().thenText("你好呀");

        TurnResult r = run(model, ToolSet.empty(), new CancellationToken());

        assertEquals(TurnOutcome.COMPLETED, r.outcome());
        assertEquals("你好呀", r.answer());
        assertEquals(List.of(Message.user("你好"), Message.assistant("你好呀")), r.newMessages());
        assertEquals(1, r.steps());
        assertInstanceOf(TurnEvent.TurnStarted.class, events.get(0));
        assertInstanceOf(TurnEvent.TurnFinished.class, events.get(events.size() - 1));
    }

    @Test
    void toolRoundTripKeepsInvariants() {
        ToolCall call = new ToolCall("c1", "echo", "{\"text\":\"hi\"}");
        ScriptedLlmAdapter model = new ScriptedLlmAdapter().thenToolCalls(call).thenText("完成");

        TurnResult r = run(model, ToolSet.of(ECHO), new CancellationToken());

        assertEquals(TurnOutcome.COMPLETED, r.outcome());
        assertEquals(List.of(
            Message.user("你好"),
            Message.assistant("", List.of(call)),     // 不变量 1：工具调用原样放回
            Message.toolResult("c1", "echo:hi"),        // 不变量 2：每个调用都有结果
            Message.assistant("完成")), r.newMessages());
        // 第二次请求里能看到工具结果，并且带着系统提示和工具清单
        LlmRequest second = model.requests().get(1);
        assertEquals("系统提示", second.system());
        assertEquals("echo", second.tools().get(0).name());
        assertEquals(Role.TOOL, second.lastMessage().role());
        assertEquals(2, r.usage().inputTokens() / 10);
    }

    @Test
    void historyIsSentBeforeNewInput() {
        ScriptedLlmAdapter model = new ScriptedLlmAdapter().thenText("ok");
        List<Message> history = List.of(Message.user("上一句"), Message.assistant("上一个回答"));

        run(model, ToolSet.empty(), new CancellationToken(), history);

        List<Message> sent = model.requests().get(0).messages();
        assertEquals(3, sent.size());
        assertEquals("上一句", sent.get(0).text());
        assertEquals("你好", sent.get(2).text());
    }

    @Test
    void unknownToolAndBadJsonBecomeErrorsForTheModel() {
        ScriptedLlmAdapter model = new ScriptedLlmAdapter()
            .thenToolCalls(new ToolCall("c1", "nope", "{}"), new ToolCall("c2", "echo", "{not json"))
            .thenText("我换个办法");

        TurnResult r = run(model, ToolSet.of(ECHO), new CancellationToken());

        assertEquals(TurnOutcome.COMPLETED, r.outcome());
        Message unknown = r.newMessages().get(2);
        Message badJson = r.newMessages().get(3);
        assertTrue(unknown.error() && unknown.text().contains("可用的工具：echo"));
        assertTrue(badJson.error() && badJson.text().contains("不是合法的 JSON"));
    }

    @Test
    void toolExceptionDoesNotBreakTheLoop() {
        Tool broken = new SimpleTool("broken", (args, ctx) -> {
            throw new IllegalStateException("炸了");
        });
        ScriptedLlmAdapter model = new ScriptedLlmAdapter()
            .thenToolCalls(new ToolCall("c1", "broken", "{}"))
            .thenText("工具坏了");

        TurnResult r = run(model, ToolSet.of(broken), new CancellationToken());

        assertEquals(TurnOutcome.COMPLETED, r.outcome());
        assertTrue(r.newMessages().get(2).text().contains("工具内部错误"));
    }

    @Test
    void maxStepsFuse() {
        ScriptedLlmAdapter model = new ScriptedLlmAdapter();
        for (int i = 0; i < 5; i++) {
            model.thenToolCalls(new ToolCall("c" + i, "echo", "{\"text\":\"again\"}"));
        }

        TurnResult r = run(model, ToolSet.of(ECHO), new CancellationToken());

        assertEquals(TurnOutcome.MAX_STEPS, r.outcome());
        assertEquals(5, r.steps());
        // 最后一条是工具结果：历史依然满足不变量，下个回合可以接着用
        assertEquals(Role.TOOL, r.newMessages().get(r.newMessages().size() - 1).role());
    }

    @Test
    void modelErrorEndsTurnAsFailed() {
        ScriptedLlmAdapter model = new ScriptedLlmAdapter().thenError(LlmException.Kind.AUTHENTICATION);

        TurnResult r = run(model, ToolSet.empty(), new CancellationToken());

        assertEquals(TurnOutcome.FAILED, r.outcome());
        assertEquals(LlmException.Kind.AUTHENTICATION, r.errorKind());
        assertEquals(List.of(Message.user("你好")), r.newMessages());
    }

    @Test
    void cancelDuringToolsClosesDanglingCalls() {
        CancellationToken cancel = new CancellationToken();
        Tool cancelling = new SimpleTool("stop", (args, ctx) -> {
            cancel.cancel(); // 模拟：执行第一个工具时用户点了停止
            return ToolResult.ok("做了一半");
        });
        ToolCall c1 = new ToolCall("c1", "stop", "{}");
        ToolCall c2 = new ToolCall("c2", "stop", "{}");
        ScriptedLlmAdapter model = new ScriptedLlmAdapter().thenToolCalls(c1, c2);

        TurnResult r = run(model, ToolSet.of(cancelling), cancel);

        assertEquals(TurnOutcome.CANCELLED, r.outcome());
        List<Message> m = r.newMessages();
        assertEquals(4, m.size()); // user, assistant(c1,c2), c1 结果, c2 结果
        assertEquals("c1", m.get(2).toolCallId());
        assertEquals("c2", m.get(3).toolCallId());
        assertTrue(m.get(3).error());
        assertFalse(events.stream().anyMatch(e -> e instanceof TurnEvent.ToolCallFinished f && f.call().id().equals("c2")));
    }

    /** 测试用的小工具：名字 + 一段逻辑 */
    private record SimpleTool(String name, Body body) implements Tool {

        interface Body {
            ToolResult call(ToolArgs args, ToolContext ctx) throws Exception;
        }

        @Override
        public String description() {
            return "测试工具 " + name;
        }

        @Override
        public String inputSchema() {
            return "{\"type\":\"object\"}";
        }

        @Override
        public ToolResult call(ToolArgs args, ToolContext context) throws Exception {
            return body.call(args, context);
        }
    }
}
