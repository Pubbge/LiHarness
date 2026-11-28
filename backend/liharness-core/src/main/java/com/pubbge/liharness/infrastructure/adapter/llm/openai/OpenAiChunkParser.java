package com.pubbge.liharness.infrastructure.adapter.llm.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.pubbge.liharness.domain.llm.error.LlmException;
import com.pubbge.liharness.domain.llm.exchange.TokenUsage;
import com.pubbge.liharness.domain.llm.stream.FinishReason;
import com.pubbge.liharness.domain.llm.stream.StreamChunk;
import com.pubbge.liharness.domain.shared.json.Json;
import java.util.ArrayList;
import java.util.List;

/**
 * 把 OpenAI 兼容服务的流式响应（一行一行的 SSE 数据）<b>翻译</b>成我们的 {@link StreamChunk}。
 *
 * <p>流式响应长这样（每行一个 JSON，最后是 [DONE]）：
 * <pre>
 * data: {"choices":[{"index":0,"delta":{"reasoning_content":"先想想"}}]}                    ← DeepSeek-R1 的思考过程
 * data: {"choices":[{"index":0,"delta":{"content":"你"}}]}
 * data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"get_time","arguments":""}}]}}]}
 * data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"tz\":\"UTC\"}"}}]}}]}
 * data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}
 * data: {"choices":[],"usage":{"prompt_tokens":120,"completion_tokens":18}}
 * data: [DONE]
 * </pre>
 *
 * <p><b>只翻译、不拼装</b>：工具调用的参数片段原样交出去，由领域层的 ResponseAssembler 按 index 拼起来；
 * “stop 却带了工具调用”“截断了的工具调用”这些不规范，也都在 ResponseAssembler 里统一处理。
 * 所以这里只需要关心一件事：这家服务的 JSON 字段叫什么。
 *
 * <p>纯粹的解析器：不做网络、不做线程，喂一行、吐出这一行对应的片段，可以单独测试。
 * 不是线程安全的：一次模型调用一个实例。
 */
final class OpenAiChunkParser {

    private String finishReason;
    private TokenUsage usage;
    private String model = "";
    private boolean done;

    /** 已经交出 Finish 了吗 */
    boolean done() {
        return done;
    }

    /**
     * 解析一行原始数据。
     *
     * @return 这一行对应的片段，可能为空；遇到 [DONE] 时返回 Usage（如果有）和 Finish
     */
    List<StreamChunk> parse(String line) throws LlmException {
        if (done || !line.startsWith("data:")) {
            return List.of(); // 空行、注释、event: 行都忽略
        }
        String data = line.substring(5).trim();
        if (data.equals("[DONE]")) {
            return finish();
        }
        if (data.isEmpty()) {
            return List.of();
        }
        JsonNode chunk;
        try {
            chunk = Json.readTree(data);
        } catch (JsonProcessingException e) {
            throw new LlmException(LlmException.Kind.SERVER, "模型服务返回了无法解析的数据：" + abbreviate(data));
        }
        // 有的服务在流的中途用一个 error 对象报错，而不是用 HTTP 状态码
        if (chunk.has("error")) {
            throw new LlmException(LlmException.Kind.SERVER, "模型服务报错：" + chunk.path("error").path("message").asText(data));
        }
        if (chunk.hasNonNull("model")) {
            model = chunk.get("model").asText();
        }
        JsonNode u = chunk.path("usage");
        if (u.isObject()) {
            usage = new TokenUsage(u.path("prompt_tokens").asLong(0), u.path("completion_tokens").asLong(0));
        }
        List<StreamChunk> out = new ArrayList<>();
        for (JsonNode choice : chunk.path("choices")) {
            if (choice.path("index").asInt(0) != 0) {
                continue; // 我们只请求一个候选回答
            }
            JsonNode d = choice.path("delta");
            String reasoning = d.path("reasoning_content").asText("");
            if (!reasoning.isEmpty()) {
                out.add(new StreamChunk.ReasoningDelta(reasoning));
            }
            String content = d.path("content").asText("");
            if (!content.isEmpty()) {
                out.add(new StreamChunk.TextDelta(content));
            }
            for (JsonNode tc : d.path("tool_calls")) {
                JsonNode fn = tc.path("function");
                out.add(new StreamChunk.ToolCallDelta(
                    tc.path("index").asInt(0),
                    tc.hasNonNull("id") ? tc.get("id").asText() : null,
                    fn.hasNonNull("name") ? fn.get("name").asText() : null,
                    fn.hasNonNull("arguments") ? fn.get("arguments").asText() : ""));
            }
            if (choice.hasNonNull("finish_reason")) {
                finishReason = choice.get("finish_reason").asText();
            }
        }
        return out;
    }

    /**
     * 流结束：交出用量和结束原因。正常情况由 [DONE] 触发；少数服务不发 [DONE]、直接断开连接，
     * 适配器在连接正常结束时调用它补上。
     */
    List<StreamChunk> finish() {
        if (done) {
            return List.of();
        }
        done = true;
        List<StreamChunk> out = new ArrayList<>();
        if (usage != null) {
            out.add(new StreamChunk.Usage(usage));
        }
        out.add(new StreamChunk.Finish(reason(finishReason), model));
        return out;
    }

    /** 各家的结束原因翻译成我们的枚举。没给或不认识的按“说完了”处理，以实际的工具调用为准 */
    static FinishReason reason(String raw) {
        if (raw == null) {
            return FinishReason.END_TURN;
        }
        return switch (raw) {
            case "tool_calls", "function_call" -> FinishReason.TOOL_USE;
            case "length" -> FinishReason.MAX_TOKENS;
            default -> FinishReason.END_TURN;
        };
    }

    private static String abbreviate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
