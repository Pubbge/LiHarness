package com.pubbge.liharness.domain.llm.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pubbge.liharness.domain.llm.stream.FinishReason;
import com.pubbge.liharness.domain.llm.exchange.LlmResponse;
import com.pubbge.liharness.domain.llm.stream.StreamChunk;
import com.pubbge.liharness.domain.llm.exchange.TokenUsage;
import com.pubbge.liharness.domain.llm.tool.ToolCall;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResponseAssemblerTest {

    private static LlmResponse assemble(StreamChunk... chunks) {
        ResponseAssembler a = new ResponseAssembler();
        for (StreamChunk c : chunks) {
            a.push(c);
        }
        return a.response();
    }

    @Test
    void joinsTextAndKeepsReasoningOutOfTheAnswer() {
        ResponseAssembler a = new ResponseAssembler();
        a.push(new StreamChunk.ReasoningDelta("用户在打招呼"));
        a.push(new StreamChunk.TextDelta("你"));
        a.push(new StreamChunk.TextDelta("好"));
        a.push(new StreamChunk.Usage(new TokenUsage(10, 2)));
        a.push(new StreamChunk.Finish(FinishReason.END_TURN, "m1"));

        LlmResponse r = a.response();
        assertEquals("你好", r.text());
        assertEquals("用户在打招呼", a.reasoning()); // 思考过程单独拿，不进回答
        assertEquals(new TokenUsage(10, 2), r.usage());
        assertEquals("m1", r.model());
    }

    @Test
    void assemblesToolCallsSplitAcrossChunksByIndex() {
        LlmResponse r = assemble(
            new StreamChunk.ToolCallDelta(0, "call_a", "read_file", ""),
            new StreamChunk.ToolCallDelta(1, "call_b", "get_time", "{}"),
            new StreamChunk.ToolCallDelta(0, null, null, "{\"path\":"),
            new StreamChunk.ToolCallDelta(0, null, null, "\"a.txt\"}"),
            new StreamChunk.Finish(FinishReason.TOOL_USE, ""));

        assertEquals(List.of(
            new ToolCall("call_a", "read_file", "{\"path\":\"a.txt\"}"),
            new ToolCall("call_b", "get_time", "{}")), r.toolCalls());
        assertEquals(FinishReason.TOOL_USE, r.finishReason());
    }

    @Test
    void toolCallsWinOverAWrongFinishReason() {
        // 有的服务明明调用了工具，却报 stop
        LlmResponse r = assemble(
            new StreamChunk.ToolCallDelta(0, "c1", "get_time", ""),
            new StreamChunk.Finish(FinishReason.END_TURN, ""));
        assertEquals(FinishReason.TOOL_USE, r.finishReason());
    }

    @Test
    void truncatedOutputDropsHalfWrittenToolCalls() {
        LlmResponse r = assemble(
            new StreamChunk.TextDelta("我来读一下"),
            new StreamChunk.ToolCallDelta(0, "c1", "read_file", "{\"pa"),
            new StreamChunk.Finish(FinishReason.MAX_TOKENS, ""));
        assertEquals(FinishReason.MAX_TOKENS, r.finishReason());
        assertTrue(r.toolCalls().isEmpty());
        assertEquals("我来读一下", r.text());
    }

    @Test
    void missingIdIsGeneratedAndNamelessFragmentsAreDropped() {
        LlmResponse r = assemble(
            new StreamChunk.ToolCallDelta(0, null, "get_time", "{}"),
            new StreamChunk.ToolCallDelta(1, "c2", null, "{}"),
            new StreamChunk.Finish(FinishReason.TOOL_USE, ""));
        assertEquals(1, r.toolCalls().size());
        assertTrue(r.toolCalls().get(0).id().startsWith("call_0_"));
    }

    @Test
    void responseOnlyAfterFinishAndNothingAfterIt() {
        ResponseAssembler a = new ResponseAssembler();
        a.push(new StreamChunk.TextDelta("x"));
        assertThrows(IllegalStateException.class, a::response);
        a.push(new StreamChunk.Finish(FinishReason.END_TURN, ""));
        assertThrows(IllegalStateException.class, () -> a.push(new StreamChunk.TextDelta("y")));
    }
}
