package com.pubbge.liharness.domain.llm.service;

import com.pubbge.liharness.domain.llm.model.valobj.FinishReason;
import com.pubbge.liharness.domain.llm.model.valobj.LlmResponse;
import com.pubbge.liharness.domain.llm.model.valobj.StreamChunk;
import com.pubbge.liharness.domain.llm.model.valobj.TokenUsage;
import com.pubbge.liharness.domain.llm.model.valobj.ToolCall;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 把一个个片段拼成一次完整的回复。
 *
 * <p>为什么放在领域层，而不是让每个适配器自己拼：拼装里有好几条<b>业务规则</b>，所有模型服务都一样：
 * <ul>
 *   <li>同一个工具调用被切成很多块，按 index 对号入座；</li>
 *   <li>结束原因和工具调用要一致：有工具调用就是 TOOL_USE（有的服务明明调用了工具，却报 stop）；</li>
 *   <li>被截断（MAX_TOKENS）时，工具调用的参数多半是半截 JSON，不能执行，整体丢掉；</li>
 *   <li>工具调用没有 id（少数本地部署的服务）时自动补一个，否则工具结果没法对号入座。</li>
 * </ul>
 * 写在这里一次，所有适配器都受益，而且可以不联网单独测试。Anthropic Java SDK 的 MessageAccumulator、
 * deepseek-harness-java 的 BlockAssembler 都是这个角色。
 *
 * <p>不是线程安全的：一次模型调用一个实例，在读流的那个线程上使用。
 */
public final class BlockAssembler {

    private final StringBuilder text = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    /** index → 正在拼装的工具调用。TreeMap 保证最后按 index 顺序输出 */
    private final Map<Integer, PartialCall> calls = new TreeMap<>();
    private TokenUsage usage = TokenUsage.ZERO;
    private StreamChunk.Finish finish;

    /** 收下一个片段 */
    public void push(StreamChunk chunk) {
        if (finish != null) {
            throw new IllegalStateException("已经收到 Finish，不能再收片段");
        }
        if (chunk instanceof StreamChunk.TextDelta d) {
            text.append(d.text());
        } else if (chunk instanceof StreamChunk.ReasoningDelta d) {
            reasoning.append(d.text());
        } else if (chunk instanceof StreamChunk.ToolCallDelta d) {
            PartialCall call = calls.computeIfAbsent(d.index(), i -> new PartialCall());
            if (d.id() != null && !d.id().isBlank()) {
                call.id = d.id();
            }
            if (d.name() != null) {
                call.name.append(d.name()); // 极少数服务会把名字也分段发
            }
            call.arguments.append(d.argumentsDelta());
        } else if (chunk instanceof StreamChunk.Usage u) {
            usage = u.usage(); // 以最后一次为准：有的服务每块都带累计用量
        } else if (chunk instanceof StreamChunk.Finish f) {
            finish = f;
        }
    }

    /** 收到 Finish 了吗 */
    public boolean finished() {
        return finish != null;
    }

    /** 到目前为止的正文 */
    public String text() {
        return text.toString();
    }

    /** 到目前为止的思考过程 */
    public String reasoning() {
        return reasoning.toString();
    }

    /**
     * 整理出完整回复。必须在收到 Finish 之后调用。
     */
    public LlmResponse response() {
        if (finish == null) {
            throw new IllegalStateException("还没有收到 Finish");
        }
        if (finish.reason() == FinishReason.MAX_TOKENS) {
            // 截断：工具调用的参数不完整，执行它只会出错，整体丢掉，按“回答被截断”处理
            return new LlmResponse(text.toString(), List.of(), FinishReason.MAX_TOKENS, usage, finish.model());
        }
        List<ToolCall> toolCalls = new ArrayList<>();
        for (Map.Entry<Integer, PartialCall> e : calls.entrySet()) {
            PartialCall c = e.getValue();
            if (c.name.length() == 0) {
                continue; // 没有名字的残片，无法执行
            }
            String id = c.id != null ? c.id : "call_" + e.getKey() + "_" + Long.toHexString(System.nanoTime());
            toolCalls.add(new ToolCall(id, c.name.toString(), c.arguments.toString()));
        }
        // 以实际的工具调用为准，而不是以服务端报告的结束原因为准
        FinishReason reason = toolCalls.isEmpty() ? FinishReason.END_TURN : FinishReason.TOOL_USE;
        return new LlmResponse(text.toString(), toolCalls, reason, usage, finish.model());
    }

    private static final class PartialCall {
        String id;
        final StringBuilder name = new StringBuilder();
        final StringBuilder arguments = new StringBuilder();
    }
}
