package com.pubbge.liharness.domain.llm.model.valobj;

import java.util.List;

/**
 * 模型的一次完整回复。
 *
 * <p>不变量：finishReason == TOOL_USE 当且仅当 toolCalls 非空。领域服务 BlockAssembler 在拼装时
 * 保证这一点（各家服务的不规范都在那里统一处理），Agent Loop 就可以放心地只看 finishReason。
 *
 * @param text       文本部分（可能为空：模型只调用工具、什么也没说）
 * @param toolCalls  工具调用
 * @param finishReason 为什么停下
 * @param usage      token 用量；模型服务没返回时为 {@link TokenUsage#ZERO}
 * @param model      实际响应的模型名（可能和请求的不同，比如服务端做了别名映射）
 */
public record LlmResponse(String text, List<ToolCall> toolCalls, FinishReason finishReason, TokenUsage usage, String model) {

    public LlmResponse {
        text = text == null ? "" : text;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        usage = usage == null ? TokenUsage.ZERO : usage;
        model = model == null ? "" : model;
        if (finishReason == null) {
            throw new IllegalArgumentException("finishReason 不能为空");
        }
        if ((finishReason == FinishReason.TOOL_USE) != !toolCalls.isEmpty()) {
            throw new IllegalArgumentException("finishReason=TOOL_USE 当且仅当有工具调用，实际 " + finishReason
                + " / " + toolCalls.size() + " 个调用");
        }
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    /**
     * 把这次回复原样变成一条助手消息，放回对话历史。
     *
     * <p>为什么必须“原样”：模型服务要求每个工具结果都能在上一条助手消息里找到对应的工具调用 id。
     * 如果只保存文本、丢掉 toolCalls，下一次请求会被直接拒绝。
     */
    public Message toAssistantMessage() {
        return Message.assistant(text, toolCalls);
    }
}
