package com.pubbge.liharness.domain.llm.model.valobj;

/**
 * 消息角色。
 *
 * <p>注意这里没有 SYSTEM：系统提示词不是对话里的一条消息，而是 {@link LlmRequest#system()} 字段。
 */
public enum Role {
    /** 用户输入 */
    USER,
    /** 模型输出：文本，和/或工具调用 */
    ASSISTANT,
    /** 工具执行结果，通过 toolCallId 对应到某一次工具调用 */
    TOOL
}
