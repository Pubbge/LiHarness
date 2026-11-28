package com.pubbge.liharness.domain.llm.stream;

/**
 * 模型为什么停下。Agent Loop 靠它决定下一步。
 */
public enum FinishReason {
    /** 模型认为说完了：这就是最终回答 */
    END_TURN,
    /** 模型要调用工具：执行工具、回填结果，再问一次模型 */
    TOOL_USE,
    /** 输出达到 maxOutputTokens 被截断：回答不完整，必须告诉用户（不能当成正常结束） */
    MAX_TOKENS
}
