package com.pubbge.liharness.domain.agent.model.valobj;

/** 回合是怎么结束的 */
public enum TurnOutcome {
    /** 模型给出了最终回答 */
    COMPLETED,
    /** 达到最大步数，被保险丝切断 */
    MAX_STEPS,
    /** 模型输出被截断（达到 maxOutputTokens） */
    MAX_TOKENS,
    /** 用户停止 */
    CANCELLED,
    /** 出错：模型调用失败，或者内部异常 */
    FAILED
}
