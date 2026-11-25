package com.pubbge.liharness.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessageTest {

    /** USER 消息必须有非空白文本；纯空格不能作为有效输入。 */
    @Test
    void userMessageMustHaveText() {
        assertThrows(IllegalArgumentException.class, () -> Message.user(" "));
    }

    /** TOOL 结果必须带 toolCallId，才能对应到之前的工具调用。 */
    @Test
    void toolResultMustHaveCallId() {
        assertThrows(IllegalArgumentException.class, () -> Message.toolResult(null, "ok"));
    }

    /** ASSISTANT 至少要有文本或工具调用；空文本的工具调用也应有效。 */
    @Test
    void assistantMustSaySomethingOrCallTool() {
        assertThrows(IllegalArgumentException.class, () -> Message.assistant(""));

        Message onlyCall = Message.assistant("", List.of(new ToolCall("c1", "get_time", null)));

        assertTrue(onlyCall.hasToolCalls());
        assertEquals("{}", onlyCall.toolCalls().get(0).argumentsJson());
    }

    /** 创建消息后，修改原列表或通过消息访问器修改列表都不应改变消息。 */
    @Test
    void toolCallsAreDefensivelyCopied() {
        List<ToolCall> calls = new ArrayList<>(List.of(new ToolCall("c1", "get_time", "{}")));
        Message message = Message.assistant("", calls);
        calls.clear();

        assertEquals(1, message.toolCalls().size());
        assertThrows(UnsupportedOperationException.class, () -> message.toolCalls().clear());
    }

    /** TOOL_USE 必须且只能与非空工具调用列表同时出现。 */
    @Test
    void responseInvariantBetweenStopReasonAndToolCalls() {
        ToolCall call = new ToolCall("c1", "get_time", "{}");

        assertThrows(IllegalArgumentException.class,
            () -> new ChatResponse("", List.of(call), StopReason.END_TURN, null, null));
        assertThrows(IllegalArgumentException.class,
            () -> new ChatResponse("hi", List.of(), StopReason.TOOL_USE, null, null));
    }

    /** 将模型回复放回历史时，助手角色、文本和工具调用都应原样保留。 */
    @Test
    void responseBecomesAssistantMessageVerbatim() {
        ToolCall call = new ToolCall("c1", "get_time", "{}");
        ChatResponse response = new ChatResponse("我查一下", List.of(call), StopReason.TOOL_USE, null, "mock");

        Message message = response.toAssistantMessage();

        assertEquals(Role.ASSISTANT, message.role());
        assertEquals("我查一下", message.text());
        assertEquals(List.of(call), message.toolCalls());
    }

    /** 请求必须有消息、首条必须来自用户，且工具名不能重复。 */
    @Test
    void requestRules() {
        assertThrows(IllegalArgumentException.class, () -> ChatRequest.of("", List.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> ChatRequest.of("", List.of(Message.assistant("hi")), List.of()));

        ToolSpec tool = new ToolSpec("get_time", "获取当前时间", null);
        assertThrows(IllegalArgumentException.class,
            () -> ChatRequest.of("", List.of(Message.user("hi")), List.of(tool, tool)));
    }

    /** 工具名只能使用允许的字符且最长 64 位；空 schema 会使用默认 schema。 */
    @Test
    void toolNameRules() {
        assertThrows(IllegalArgumentException.class, () -> new ToolSpec("get time", "x", null));
        assertThrows(IllegalArgumentException.class, () -> new ToolSpec("a".repeat(65), "x", null));
        assertEquals(ToolSpec.NO_ARGS_SCHEMA, new ToolSpec("get_time", "x", "").inputSchemaJson());
    }
}
