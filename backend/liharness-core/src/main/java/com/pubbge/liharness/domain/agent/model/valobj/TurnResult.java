package com.pubbge.liharness.domain.agent.model.valobj;

import com.pubbge.liharness.domain.llm.error.LlmException;
import com.pubbge.liharness.domain.llm.exchange.TokenUsage;
import com.pubbge.liharness.domain.llm.message.Message;
import java.util.List;

/**
 * 一个回合的结果。
 *
 * @param turnId       回合 id
 * @param outcome      怎么结束的
 * @param answer       最后一次模型回复的文本（可能为空）
 * @param newMessages  本回合新增的消息：用户输入、模型回复、工具结果。<b>满足 Agent Loop 不变量</b>，
 *                     可以直接追加到会话历史里
 * @param usage        本回合所有模型调用的 token 用量之和
 * @param steps        调用了几次模型
 * @param errorMessage 失败原因（仅 FAILED / MAX_STEPS / MAX_TOKENS）
 * @param errorKind    模型错误的分类（仅模型调用失败时）
 */
public record TurnResult(
        String turnId,
        TurnOutcome outcome,
        String answer,
        List<Message> newMessages,
        TokenUsage usage,
        int steps,
        String errorMessage,
        LlmException.Kind errorKind) {

    public TurnResult {
        answer = answer == null ? "" : answer;
        newMessages = List.copyOf(newMessages);
        usage = usage == null ? TokenUsage.ZERO : usage;
    }

    public boolean succeeded() {
        return outcome == TurnOutcome.COMPLETED;
    }
}
