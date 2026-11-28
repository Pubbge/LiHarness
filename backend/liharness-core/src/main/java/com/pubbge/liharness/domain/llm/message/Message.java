package com.pubbge.liharness.domain.llm.message;

import com.pubbge.liharness.domain.llm.tool.ToolCall;
import com.pubbge.liharness.domain.shared.validate.Texts;
import java.util.List;

/**
 * 对话里的一条消息。不可变。
 *
 * <p>三种角色能带的字段不同，构造时就校验，非法消息根本创建不出来：
 * <pre>
 * 角色        text   toolCalls   toolCallId   error
 * USER        必填    必须为空     必须为 null   false
 * ASSISTANT   可空    可以有       必须为 null   false
 * TOOL        可空    必须为空     必填          可以 true
 * </pre>
 * 建议用静态工厂方法创建，而不是直接 new。
 *
 * @param role       角色
 * @param text       文本内容，永远不为 null
 * @param toolCalls  仅 ASSISTANT：模型请求的工具调用，永远不为 null
 * @param toolCallId 仅 TOOL：这条结果回应的是哪次调用
 * @param error      仅 TOOL：工具执行失败。失败也要告诉模型，让它自己决定换个办法（第 06 章）
 */
public record Message(Role role, String text, List<ToolCall> toolCalls, String toolCallId, boolean error) {

    public Message {
        if (role == null) {
            throw new IllegalArgumentException("role 不能为空");
        }
        text = text == null ? "" : text;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls); // 防御性拷贝，外面改不了
        switch (role) {
            case USER -> {
                Texts.requireNonBlank(text, "用户消息的 text");
                require(toolCalls.isEmpty() && toolCallId == null && !error, "用户消息只能有文本");
            }
            case ASSISTANT -> {
                require(toolCallId == null && !error, "助手消息不能带 toolCallId / error");
                require(!text.isEmpty() || !toolCalls.isEmpty(), "助手消息不能既没文本又没工具调用");
            }
            case TOOL -> {
                Texts.requireNonBlank(toolCallId, "工具结果的 toolCallId");
                require(toolCalls.isEmpty(), "工具结果不能再带工具调用");
            }
        }
    }

    public static Message user(String text) {
        return new Message(Role.USER, text, List.of(), null, false);
    }

    public static Message assistant(String text) {
        return new Message(Role.ASSISTANT, text, List.of(), null, false);
    }

    public static Message assistant(String text, List<ToolCall> toolCalls) {
        return new Message(Role.ASSISTANT, text, toolCalls, null, false);
    }

    public static Message toolResult(String toolCallId, String content) {
        return new Message(Role.TOOL, content, List.of(), toolCallId, false);
    }

    public static Message toolError(String toolCallId, String errorMessage) {
        return new Message(Role.TOOL, errorMessage, List.of(), toolCallId, true);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
