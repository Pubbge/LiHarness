package com.pubbge.liharness.domain.llm.model.valobj;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessageTest {

    @Test
    void userMessageMustHaveText() {
        assertThrows(IllegalArgumentException.class, () -> Message.user(" "));
    }

    @Test
    void toolResultMustHaveCallId() {
        assertThrows(IllegalArgumentException.class, () -> Message.toolResult(null, "ok"));
    }

    @Test
    void assistantMustSaySomethingOrCallTool() {
        assertThrows(IllegalArgumentException.class, () -> Message.assistant(""));
        Message onlyCall = Message.assistant("", List.of(new ToolCall("c1", "get_time", null)));
        assertTrue(onlyCall.hasToolCalls());
        assertEquals("{}", onlyCall.toolCalls().get(0).argumentsJson());
    }

    @Test
    void toolCallsAreDefensivelyCopied() {
        List<ToolCall> calls = new ArrayList<>(List.of(new ToolCall("c1", "get_time", "{}")));
        Message m = Message.assistant("", calls);
        calls.clear();
        assertEquals(1, m.toolCalls().size());
        assertThrows(UnsupportedOperationException.class, () -> m.toolCalls().clear());
    }

    @Test
    void responseInvariantBetweenFinishReasonAndToolCalls() {
        ToolCall call = new ToolCall("c1", "get_time", "{}");
        assertThrows(IllegalArgumentException.class,
            () -> new LlmResponse("", List.of(call), FinishReason.END_TURN, null, null));
        assertThrows(IllegalArgumentException.class,
            () -> new LlmResponse("hi", List.of(), FinishReason.TOOL_USE, null, null));
    }

    @Test
    void responseBecomesAssistantMessageVerbatim() {
        ToolCall call = new ToolCall("c1", "get_time", "{}");
        LlmResponse r = new LlmResponse("我查一下", List.of(call), FinishReason.TOOL_USE, null, "mock");
        Message m = r.toAssistantMessage();
        assertEquals(Role.ASSISTANT, m.role());
        assertEquals("我查一下", m.text());
        assertEquals(List.of(call), m.toolCalls());
    }

    @Test
    void requestRules() {
        assertThrows(IllegalArgumentException.class, () -> LlmRequest.of("", List.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> LlmRequest.of("", List.of(Message.assistant("hi")), List.of()));
        ToolSpec t = new ToolSpec("get_time", "获取当前时间", null);
        assertThrows(IllegalArgumentException.class,
            () -> LlmRequest.of("", List.of(Message.user("hi")), List.of(t, t)));
    }

    @Test
    void toolNameRules() {
        assertThrows(IllegalArgumentException.class, () -> new ToolSpec("get time", "x", null));
        assertThrows(IllegalArgumentException.class, () -> new ToolSpec("a".repeat(65), "x", null));
        assertEquals(ToolSpec.NO_ARGS_SCHEMA, new ToolSpec("get_time", "x", "").inputSchemaJson());
    }
}
