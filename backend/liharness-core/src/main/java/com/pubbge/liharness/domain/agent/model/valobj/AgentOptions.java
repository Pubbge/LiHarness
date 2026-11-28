package com.pubbge.liharness.domain.agent.model.valobj;

/**
 * Agent Loop 的参数。
 *
 * @param maxSteps 一个回合最多调用模型多少次（保险丝）。理由见第 06 章“参数怎么定”
 */
public record AgentOptions(int maxSteps) {

    public static final AgentOptions DEFAULT = new AgentOptions(25);

    public AgentOptions {
        if (maxSteps < 1) {
            throw new IllegalArgumentException("maxSteps 至少为 1");
        }
    }
}
