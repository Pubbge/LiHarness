package com.pubbge.liharness.infrastructure.adapter.llm.mock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pubbge.liharness.domain.llm.adapter.port.LlmStream;
import com.pubbge.liharness.domain.llm.stream.FinishReason;
import com.pubbge.liharness.domain.llm.error.LlmException;
import com.pubbge.liharness.domain.llm.exchange.LlmRequest;
import com.pubbge.liharness.domain.llm.exchange.LlmResponse;
import com.pubbge.liharness.domain.llm.message.Message;
import com.pubbge.liharness.domain.llm.stream.StreamChunk;
import com.pubbge.liharness.domain.llm.tool.ToolCall;
import com.pubbge.liharness.domain.llm.tool.ToolSpec;
import com.pubbge.liharness.domain.llm.service.LlmGenerationService;
import com.pubbge.liharness.domain.shared.cancel.CancellationToken;
import com.pubbge.liharness.domain.shared.cancel.CancelledException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MockLlmAdapterTest {

    private static final ToolSpec TIME = new ToolSpec("get_time", "获取当前时间", null);

    private final MockLlmAdapter model = new MockLlmAdapter(Duration.ZERO);

    private static LlmRequest ask(String text, ToolSpec... tools) {
        return LlmRequest.of("", List.of(Message.user(text)), List.of(tools));
    }

    private LlmResponse generate(LlmRequest request) throws LlmException {
        return new LlmGenerationService(model).generate(request, new CancellationToken());
    }

    @Test
    void streamsCharacterByCharacterThenUsageThenFinish() throws Exception {
        List<StreamChunk> chunks = new ArrayList<>();
        try (LlmStream stream = model.stream(ask("你好😀"))) {
            StreamChunk c;
            do {
                c = stream.next();
                chunks.add(c);
            } while (!(c instanceof StreamChunk.Finish));
        }
        assertInstanceOf(StreamChunk.Usage.class, chunks.get(chunks.size() - 2));
        StreamChunk.Finish finish = (StreamChunk.Finish) chunks.get(chunks.size() - 1);
        assertEquals(FinishReason.END_TURN, finish.reason());
        List<String> deltas = chunks.stream().filter(c -> c instanceof StreamChunk.TextDelta)
            .map(c -> ((StreamChunk.TextDelta) c).text()).toList();
        assertTrue(deltas.contains("😀")); // emoji 没被切成两半
        assertEquals("（Mock）你说的是：你好😀", String.join("", deltas));
    }

    @Test
    void asksForToolWhenToolIsOffered() throws Exception {
        LlmResponse r = generate(ask("现在几点了", TIME));

        assertEquals(FinishReason.TOOL_USE, r.finishReason());
        assertEquals("get_time", r.toolCalls().get(0).name());
    }

    @Test
    void doesNotCallToolThatWasNotOffered() throws Exception {
        assertEquals(FinishReason.END_TURN, generate(ask("现在几点了")).finishReason());
    }

    @Test
    void answersAfterToolResult() throws Exception {
        ToolCall call = new ToolCall("call_1", "get_time", "{}");
        List<Message> history = List.of(
            Message.user("现在几点了"),
            Message.assistant("", List.of(call)),
            Message.toolResult("call_1", "2026-11-25 10:00"));

        LlmResponse r = generate(LlmRequest.of("", history, List.of(TIME)));

        assertEquals(FinishReason.END_TURN, r.finishReason());
        assertTrue(r.text().contains("2026-11-25 10:00"));
    }

    @Test
    void failCommandThrowsRetryableErrorFromNext() {
        LlmStream stream = model.stream(ask("/fail")); // stream() 本身不抛异常
        LlmException e = assertThrows(LlmException.class, stream::next);
        assertEquals(LlmException.Kind.SERVER, e.kind());
        assertTrue(e.retryable());
    }

    @Test
    void closeStopsTheStream() throws Exception {
        try (LlmStream stream = model.stream(ask("一段比较长的输入"))) {
            stream.next();
            stream.next();
            stream.close();
            assertThrows(CancelledException.class, stream::next);
        }
    }

    @Test
    void closeFromAnotherThreadWakesUpAWaitingNext() throws Exception {
        MockLlmAdapter slow = new MockLlmAdapter(Duration.ofSeconds(10)); // 每个字要等 10 秒
        LlmStream stream = slow.stream(ask("hi"));
        new Thread(() -> {
            sleep(200);
            stream.close();
        }).start();
        long start = System.currentTimeMillis();
        assertThrows(CancelledException.class, stream::next);
        assertTrue(System.currentTimeMillis() - start < 3000, "close 之后应该立刻醒来，而不是等满 10 秒");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
