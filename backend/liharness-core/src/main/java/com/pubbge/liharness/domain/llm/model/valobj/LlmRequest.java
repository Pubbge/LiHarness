package com.pubbge.liharness.domain.llm.model.valobj;

import java.util.List;

/**
 * 一次模型调用的输入。
 *
 * @param system          系统提示词（可以为空字符串）。单独一个字段，不混在 messages 里
 * @param messages        对话历史，至少一条，第一条必须是用户消息
 * @param tools           这次允许模型使用的工具
 * @param maxOutputTokens 本次最多输出多少 token
 */
public record LlmRequest(String system, List<Message> messages, List<ToolSpec> tools, int maxOutputTokens) {

    /** 默认输出上限。理由见教程第 04 章“参数怎么定” */
    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 8192;

    public LlmRequest {
        system = system == null ? "" : system;
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages 不能为空");
        }
        if (messages.get(0).role() != Role.USER) {
            throw new IllegalArgumentException("第一条消息必须是用户消息");
        }
        if (maxOutputTokens <= 0) {
            throw new IllegalArgumentException("maxOutputTokens 必须为正数");
        }
        long distinctNames = tools.stream().map(ToolSpec::name).distinct().count();
        if (distinctNames != tools.size()) {
            throw new IllegalArgumentException("工具名重复");
        }
    }

    public static LlmRequest of(String system, List<Message> messages, List<ToolSpec> tools) {
        return new LlmRequest(system, messages, tools, DEFAULT_MAX_OUTPUT_TOKENS);
    }

    /** 最后一条消息（调用方保证 messages 非空） */
    public Message lastMessage() {
        return messages.get(messages.size() - 1);
    }

    public boolean hasTool(String name) {
        return tools.stream().anyMatch(t -> t.name().equals(name));
    }
}
