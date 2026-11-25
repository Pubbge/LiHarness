package com.pubbge.liharness.core.llm;

/**
 * 一次模型调用的 token 用量。
 *
 * <p>输入和输出分开记：两者单价不同（输出通常贵好几倍），而且输入 token 数就是“上下文有多满”
 * 的直接读数
 */
public record Usage(long inputTokens, long outputTokens) {

    public static final Usage ZERO = new Usage(0, 0);

    public Usage {
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("token 数不能为负");
        }
    }

    public long total() {
        return inputTokens + outputTokens;
    }

    /** 累加，用于统计一个回合里多次模型调用的总用量 */
    public Usage plus(Usage other) {
        return new Usage(inputTokens + other.inputTokens, outputTokens + other.outputTokens);
    }
}
