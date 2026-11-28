package com.pubbge.liharness.domain.agent.contract.outbound;

import com.pubbge.liharness.domain.agent.model.valobj.TurnEvent;

/**
 * 回合事件的接收方。
 *
 * <p>约定：在回合线程上同步回调，事件按发生顺序到达；实现方不要做耗时操作，也不要抛异常
 * （抛了也会被 Agent Loop 吞掉并记日志，不会影响回合）。
 */
@FunctionalInterface
public interface ITurnEventListener {

    void onEvent(TurnEvent event);

    ITurnEventListener NONE = event -> { };
}
