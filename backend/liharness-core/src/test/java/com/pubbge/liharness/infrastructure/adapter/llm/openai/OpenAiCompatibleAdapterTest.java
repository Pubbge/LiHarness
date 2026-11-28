package com.pubbge.liharness.infrastructure.adapter.llm.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.pubbge.liharness.domain.llm.adapter.port.LlmChatModelAdapter;
import com.pubbge.liharness.domain.llm.adapter.port.LlmStream;
import com.pubbge.liharness.domain.llm.error.LlmException;
import com.pubbge.liharness.domain.llm.exchange.LlmRequest;
import com.pubbge.liharness.domain.llm.exchange.LlmResponse;
import com.pubbge.liharness.domain.llm.exchange.TokenUsage;
import com.pubbge.liharness.domain.llm.message.Message;
import com.pubbge.liharness.domain.llm.service.LlmGenerationService;
import com.pubbge.liharness.domain.llm.stream.FinishReason;
import com.pubbge.liharness.domain.llm.stream.StreamChunk;
import com.pubbge.liharness.domain.llm.tool.ToolCall;
import com.pubbge.liharness.domain.llm.tool.ToolSpec;
import com.pubbge.liharness.domain.shared.cancel.CancellationToken;
import com.pubbge.liharness.domain.shared.cancel.CancelledException;
import com.pubbge.liharness.domain.shared.json.Json;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleAdapterTest {

    private static final ToolSpec TIME = new ToolSpec("get_time", "获取时间", null);

    private static OpenAiCompatibleAdapter model(FakeOpenAiServer server, Duration idle) {
        return new OpenAiCompatibleAdapter(new OpenAiConfig(server.baseUrl(), "sk-test", "test-model", idle),
            HttpClient.newHttpClient());
    }

    /** 生成完整回复，同时收集所有片段（适配器只翻译，拼装交给领域层的 LlmGenerationService） */
    private static LlmResponse generate(LlmChatModelAdapter model, LlmRequest request, List<StreamChunk> chunks)
            throws LlmException {
        return new LlmGenerationService(model).generate(request, new CancellationToken(), chunks::add);
    }

    private static LlmResponse generate(LlmChatModelAdapter model, LlmRequest request) throws LlmException {
        return generate(model, request, new ArrayList<>());
    }

    private static LlmRequest ask(String text) {
        return LlmRequest.of("你是助手", List.of(Message.user(text)), List.of(TIME));
    }

    @Test
    void streamsTextAndReadsUsage() throws Exception {
        try (FakeOpenAiServer server = new FakeOpenAiServer(ex -> FakeOpenAiServer.sse(ex, 0,
            "{\"model\":\"test-model-0613\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"你\"}}]}",
            "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"好\"},\"finish_reason\":\"stop\"}]}",
            "{\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":2}}",
            "[DONE]"))) {
            List<StreamChunk> chunks = new ArrayList<>();

            LlmResponse r = generate(model(server, Duration.ofSeconds(5)), ask("hi"), chunks);

            assertEquals("你好", r.text());
            assertEquals(List.of(new StreamChunk.TextDelta("你"), new StreamChunk.TextDelta("好"),
                new StreamChunk.Usage(new TokenUsage(12, 2)),
                new StreamChunk.Finish(FinishReason.END_TURN, "test-model-0613")), chunks); // 适配器只翻译
            assertEquals(FinishReason.END_TURN, r.finishReason());
            assertEquals(12, r.usage().inputTokens());
            assertEquals("test-model-0613", r.model());
            assertEquals("Bearer sk-test", server.authHeaders.get(0));

            JsonNode body = Json.readTree(server.requestBodies.get(0));
            assertEquals("system", body.path("messages").get(0).path("role").asText());
            assertEquals("get_time", body.path("tools").get(0).path("function").path("name").asText());
            assertTrue(body.path("stream_options").path("include_usage").asBoolean());
        }
    }

    @Test
    void assemblesToolCallsSplitAcrossChunks() throws Exception {
        try (FakeOpenAiServer server = new FakeOpenAiServer(ex -> FakeOpenAiServer.sse(ex, 0,
            "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_a\",\"type\":\"function\","
                + "\"function\":{\"name\":\"get_time\",\"arguments\":\"\"}}]}}]}",
            "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"timezone\"}}]}}]}",
            "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\":\\\"UTC\\\"}\"}}]}}]}",
            // 有的服务即使有工具调用也给 stop：以工具调用为准
            "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}",
            "[DONE]"))) {

            LlmResponse r = generate(model(server, Duration.ofSeconds(5)), ask("几点"));

            assertEquals(FinishReason.TOOL_USE, r.finishReason());
            assertEquals(List.of(new ToolCall("call_a", "get_time", "{\"timezone\":\"UTC\"}")), r.toolCalls());
        }
    }

    @Test
    void reasoningContentBecomesReasoningDeltaAndMissingDoneIsTolerated() throws Exception {
        // DeepSeek-R1 这类推理模型先输出 reasoning_content；这个假服务最后不发 [DONE]，直接结束连接
        try (FakeOpenAiServer server = new FakeOpenAiServer(ex -> FakeOpenAiServer.sse(ex, 0,
            "{\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"用户在问好\"}}]}",
            "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"你好\"},\"finish_reason\":\"stop\"}]}"))) {
            List<StreamChunk> chunks = new ArrayList<>();

            LlmResponse r = generate(model(server, Duration.ofSeconds(5)), ask("hi"), chunks);

            assertEquals(new StreamChunk.ReasoningDelta("用户在问好"), chunks.get(0));
            assertEquals("你好", r.text()); // 思考过程不进入回答
            assertTrue(chunks.get(chunks.size() - 1) instanceof StreamChunk.Finish);
        }
    }

    @Test
    void sendsToolHistoryInOpenAiFormat() throws Exception {
        try (FakeOpenAiServer server = new FakeOpenAiServer(ex -> FakeOpenAiServer.sse(ex, 0,
            "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}", "[DONE]"))) {
            ToolCall call = new ToolCall("c1", "get_time", "{}");
            LlmRequest req = LlmRequest.of("", List.of(Message.user("几点"), Message.assistant("", List.of(call)),
                Message.toolError("c1", "超时了")), List.of(TIME));

            generate(model(server, Duration.ofSeconds(5)), req);

            JsonNode messages = Json.readTree(server.requestBodies.get(0)).path("messages");
            assertEquals(3, messages.size()); // 系统提示为空时不发 system 消息
            assertTrue(messages.get(1).path("content").isNull());
            assertEquals("c1", messages.get(1).path("tool_calls").get(0).path("id").asText());
            assertEquals("c1", messages.get(2).path("tool_call_id").asText());
            assertEquals("[工具执行失败] 超时了", messages.get(2).path("content").asText());
        }
    }

    @Test
    void truncatedToolCallBecomesMaxTokens() throws Exception {
        try (FakeOpenAiServer server = new FakeOpenAiServer(ex -> FakeOpenAiServer.sse(ex, 0,
            "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c\",\"function\":{\"name\":\"get_time\","
                + "\"arguments\":\"{\\\"time\"}}]},\"finish_reason\":\"length\"}]}",
            "[DONE]"))) {
            LlmResponse r = generate(model(server, Duration.ofSeconds(5)), ask("x"));
            assertEquals(FinishReason.MAX_TOKENS, r.finishReason());
            assertTrue(r.toolCalls().isEmpty());
        }
    }

    @Test
    void mapsHttpErrors() throws Exception {
        assertKind(401, "{\"error\":{\"message\":\"Incorrect API key\"}}", null, LlmException.Kind.AUTHENTICATION);
        assertKind(402, "{\"error\":{\"message\":\"Insufficient Balance\"}}", null, LlmException.Kind.QUOTA_EXCEEDED);
        assertKind(400, "{\"error\":{\"message\":\"This model's maximum context length is 8192 tokens\"}}", null,
            LlmException.Kind.CONTEXT_TOO_LONG);
        LlmException e = assertKind(429, "{\"error\":{\"message\":\"Rate limit\"}}", "2", LlmException.Kind.RATE_LIMITED);
        assertEquals(Duration.ofSeconds(2), e.retryAfter().orElseThrow());
        assertTrue(e.getMessage().contains("Rate limit"));
    }

    private static LlmException assertKind(int status, String body, String retryAfter, LlmException.Kind kind)
            throws Exception {
        try (FakeOpenAiServer server = new FakeOpenAiServer(ex -> FakeOpenAiServer.error(ex, status, body, retryAfter))) {
            LlmException e = assertThrows(LlmException.class,
                () -> generate(model(server, Duration.ofSeconds(5)), ask("x")));
            assertEquals(kind, e.kind(), e.getMessage());
            return e;
        }
    }

    @Test
    void closeStopsReadingImmediately() throws Exception {
        String[] many = new String[200];
        for (int i = 0; i < many.length; i++) {
            many[i] = "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"字\"}}]}";
        }
        try (FakeOpenAiServer server = new FakeOpenAiServer(ex -> FakeOpenAiServer.sse(ex, 50, many))) {
            LlmStream stream = model(server, Duration.ofSeconds(5)).stream(ask("x"));
            for (int i = 0; i < 3; i++) {
                stream.next();
            }
            // 模拟另一个线程（用户点了停止）关闭流：阻塞中的 next() 应该立刻醒来
            new Thread(stream::close).start();
            long start = System.currentTimeMillis();
            assertThrows(CancelledException.class, () -> {
                while (true) {
                    stream.next();
                }
            });
            assertTrue(System.currentTimeMillis() - start < 3000);
        }
    }

    @Test
    void streamReturnsImmediatelyAndErrorsComeFromNext() throws Exception {
        try (FakeOpenAiServer server = new FakeOpenAiServer(ex -> {
            Thread.sleep(1000); // 服务器很慢才响应
            FakeOpenAiServer.error(ex, 401, "{}", null);
        })) {
            long start = System.currentTimeMillis();
            LlmStream stream = model(server, Duration.ofSeconds(5)).stream(ask("x"));
            assertTrue(System.currentTimeMillis() - start < 500, "stream() 不应该等响应");
            LlmException e = assertThrows(LlmException.class, stream::next);
            assertEquals(LlmException.Kind.AUTHENTICATION, e.kind());
        }
    }

    @Test
    void idleTimeoutIsNetworkError() throws Exception {
        try (FakeOpenAiServer server = new FakeOpenAiServer(ex -> {
            FakeOpenAiServer.sse(ex, 0, "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"a\"}}]}");
            Thread.sleep(3000); // 发了一行之后卡住
        })) {
            LlmException e = assertThrows(LlmException.class, () -> generate(model(server, Duration.ofSeconds(1)), ask("x")));
            assertEquals(LlmException.Kind.NETWORK, e.kind());
            assertTrue(e.retryable());
        }
    }

    @Test
    void connectionRefusedIsNetworkError() {
        OpenAiCompatibleAdapter m = new OpenAiCompatibleAdapter(
            new OpenAiConfig("http://127.0.0.1:1/v1", "k", "m", Duration.ofSeconds(5)), HttpClient.newHttpClient());
        LlmException e = assertThrows(LlmException.class,
            () -> generate(m, ask("x")));
        assertEquals(LlmException.Kind.NETWORK, e.kind());
    }
}
