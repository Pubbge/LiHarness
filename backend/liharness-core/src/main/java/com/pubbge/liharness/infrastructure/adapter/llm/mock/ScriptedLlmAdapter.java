package com.pubbge.liharness.infrastructure.adapter.llm.mock;

import com.pubbge.liharness.domain.llm.adapter.port.LlmChatModelAdapter;
import com.pubbge.liharness.domain.llm.adapter.port.LlmStream;
import com.pubbge.liharness.domain.llm.error.LlmException;
import com.pubbge.liharness.domain.llm.exchange.LlmRequest;
import com.pubbge.liharness.domain.llm.exchange.LlmResponse;
import com.pubbge.liharness.domain.llm.exchange.TokenUsage;
import com.pubbge.liharness.domain.llm.service.LlmStreams;
import com.pubbge.liharness.domain.llm.stream.FinishReason;
import com.pubbge.liharness.domain.llm.tool.ToolCall;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * 按剧本回答的测试模型：预先排好每一次调用要返回什么，并记录收到的每一个请求。
 *
 * <p>和 {@link MockLlmAdapter} 的区别：Mock 按规则“看输入回答”，适合界面演示；
 * Scripted 按顺序“背台词”，适合精确测试 Agent Loop 的每一步。
 */
public final class ScriptedLlmAdapter implements LlmChatModelAdapter {

    /** 剧本里的一步：看到请求，给出回复（或者抛出错误） */
    @FunctionalInterface
    public interface Step {
        LlmResponse respond(LlmRequest request) throws LlmException;
    }

    private final Deque<Step> script = new ArrayDeque<>();
    private final List<LlmRequest> requests = Collections.synchronizedList(new ArrayList<>());

    public ScriptedLlmAdapter then(Step step) {
        script.addLast(step);
        return this;
    }

    /** 下一次回复一段文本并结束 */
    public ScriptedLlmAdapter thenText(String text) {
        return then(r -> new LlmResponse(text, List.of(), FinishReason.END_TURN, new TokenUsage(10, 5), "scripted"));
    }

    /** 下一次请求调用工具 */
    public ScriptedLlmAdapter thenToolCalls(ToolCall... calls) {
        return then(r -> new LlmResponse("", List.of(calls), FinishReason.TOOL_USE, new TokenUsage(10, 5), "scripted"));
    }

    /** 下一次调用失败 */
    public ScriptedLlmAdapter thenError(LlmException.Kind kind) {
        return then(r -> {
            throw new LlmException(kind, "剧本里的错误：" + kind);
        });
    }

    /** 到目前为止收到的所有请求，用来断言“模型看到了什么” */
    public List<LlmRequest> requests() {
        return List.copyOf(requests);
    }

    @Override
    public String id() {
        return "scripted";
    }

    @Override
    public synchronized LlmStream stream(LlmRequest request) {
        requests.add(request);
        Step step = script.pollFirst();
        if (step == null) {
            throw new IllegalStateException("剧本已经演完了，但模型又被调用了第 " + requests.size() + " 次");
        }
        try {
            return LlmStreams.of(step.respond(request));
        } catch (LlmException e) {
            return LlmStreams.failed(e); // 错误从 next() 抛出，和真实模型一致
        }
    }
}
