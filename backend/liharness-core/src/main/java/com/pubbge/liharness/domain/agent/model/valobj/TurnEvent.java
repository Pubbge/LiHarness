package com.pubbge.liharness.domain.agent.model.valobj;

import com.pubbge.liharness.domain.llm.tool.ToolCall;
import com.pubbge.liharness.domain.spi.tool.ToolResult;

/**
 * 回合进行中发生的事。前端靠它实时显示进度。
 *
 * <p>为什么用 sealed 接口：事件种类是有限、确定的，每种事件的字段又完全不同。sealed 明确声明
 * “就这几种”，读代码的人和 IDE 都能一眼看到全部种类，外部也无法再偷偷加一种。
 * 升级到 Java 21 以后，{@code switch} 可以按类型匹配，漏处理一种会直接编译报错。
 *
 * <p>对比 {@code Message}：消息的字段高度重叠、通常被整体处理，所以用一个 record；
 * 事件的字段各不相同、通常要分别处理，所以用 sealed 接口。
 */
public sealed interface TurnEvent {

    /** 回合开始 */
    record TurnStarted(String sessionId, String turnId, String modelId) implements TurnEvent {
    }

    /** 模型输出的一小段文本 */
    record TextDelta(String text) implements TurnEvent {
    }

    /** 开始执行一个工具 */
    record ToolCallStarted(ToolCall call) implements TurnEvent {
    }

    /** 工具执行中的进度（比如命令输出的最新一行） */
    record ToolProgress(String toolCallId, String message) implements TurnEvent {
    }

    /** 工具执行完毕 */
    record ToolCallFinished(ToolCall call, ToolResult result, long durationMillis) implements TurnEvent {
    }

    /** 回合结束。每个回合有且只有一个，并且一定是最后一个事件 */
    record TurnFinished(TurnResult result) implements TurnEvent {
    }
}
