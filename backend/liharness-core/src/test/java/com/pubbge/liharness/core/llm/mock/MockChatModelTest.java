package com.pubbge.liharness.core.llm.mock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pubbge.liharness.core.common.CancellationToken;
import com.pubbge.liharness.core.common.CancelledException;
import com.pubbge.liharness.core.llm.ChatRequest;
import com.pubbge.liharness.core.llm.ChatResponse;
import com.pubbge.liharness.core.llm.Message;
import com.pubbge.liharness.core.llm.ModelException;
import com.pubbge.liharness.core.llm.StopReason;
import com.pubbge.liharness.core.llm.StreamListener;
import com.pubbge.liharness.core.llm.ToolCall;
import com.pubbge.liharness.core.llm.ToolSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MockChatModelTest {

    private static final ToolSpec TIME = new ToolSpec("get_time", "获取当前时间", null);

    private final MockChatModel model = new MockChatModel(Duration.ZERO);

    /** 普通输入会被 Mock 回答；流式片段拼接后等于完整回复，emoji 不会被切坏。 */
    @Test
    void echoesAndStreams() throws Exception {
        List<String> deltas = new ArrayList<>();
        ChatResponse response = model.chat(
            ChatRequest.of("", List.of(Message.user("你好😀")), List.of()),
            deltas::add,
            CancellationToken.none());

        assertEquals(StopReason.END_TURN, response.stopReason());
        assertEquals(response.text(), String.join("", deltas));
        assertTrue(deltas.contains("😀"));
    }

    /** 用户问时间且请求提供了 get_time 工具时，Mock 应返回工具调用请求。 */
    @Test
    void asksForToolWhenToolIsOffered() throws Exception {
        ChatResponse response = model.chat(
            ChatRequest.of("", List.of(Message.user("现在几点了")), List.of(TIME)),
            StreamListener.NONE,
            CancellationToken.none());

        assertEquals(StopReason.TOOL_USE, response.stopReason());
        assertEquals("get_time", response.toolCalls().get(0).name());
    }

    /** 没有把工具放进请求时，即使用户问时间，Mock 也不能调用它。 */
    @Test
    void doesNotCallToolThatWasNotOffered() throws Exception {
        ChatResponse response = model.chat(
            ChatRequest.of("", List.of(Message.user("现在几点了")), List.of()),
            StreamListener.NONE,
            CancellationToken.none());

        assertEquals(StopReason.END_TURN, response.stopReason());
    }

    /** 收到工具执行结果后，Mock 应把结果组织成最终回答并正常结束。 */
    @Test
    void answersAfterToolResult() throws Exception {
        ToolCall call = new ToolCall("call_1", "get_time", "{}");
        List<Message> history = List.of(
            Message.user("现在几点了"),
            Message.assistant("", List.of(call)),
            Message.toolResult("call_1", "2026-11-25 10:00"));

        ChatResponse response = model.chat(
            ChatRequest.of("", history, List.of(TIME)),
            StreamListener.NONE,
            CancellationToken.none());

        assertEquals(StopReason.END_TURN, response.stopReason());
        assertTrue(response.text().contains("2026-11-25 10:00"));
    }

    /** 特殊输入 /fail 用于演练服务端错误，并应被分类为可重试的 SERVER 错误。 */
    @Test
    void failCommandThrowsRetryableError() {
        ModelException exception = assertThrows(ModelException.class, () -> model.chat(
            ChatRequest.of("", List.of(Message.user("/fail")), List.of()),
            StreamListener.NONE,
            CancellationToken.none()));

        assertEquals(ModelException.Kind.SERVER, exception.kind());
        assertTrue(exception.retryable());
    }

    /** 流式输出过程中取消令牌后，模型必须抛取消异常，不能返回半截回复。 */
    @Test
    void cancelMidStream() {
        CancellationToken token = new CancellationToken();
        List<String> deltas = new ArrayList<>();
        StreamListener cancelAfterThree = delta -> {
            deltas.add(delta);
            if (deltas.size() == 3) {
                token.cancel();
            }
        };

        assertThrows(CancelledException.class, () -> model.chat(
            ChatRequest.of("", List.of(Message.user("一段比较长的输入")), List.of()), cancelAfterThree, token));
        assertEquals(3, deltas.size());
    }
}
