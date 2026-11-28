package com.pubbge.liharness.domain.tool.model.valobj;

import com.pubbge.liharness.domain.llm.message.Message;
import com.pubbge.liharness.domain.llm.tool.ToolCall;
import com.pubbge.liharness.domain.spi.tool.ToolResult;
import java.time.Duration;

/**
 * 一次工具调用的结局。
 *
 * @param call     模型发起的调用
 * @param result   结果（成功或失败都有）
 * @param duration 耗时
 */
public record ToolOutcome(ToolCall call, ToolResult result, Duration duration) {

    /** 变成回填给模型的 tool 消息 */
    public Message toMessage() {
        return result.error()
            ? Message.toolError(call.id(), result.content())
            : Message.toolResult(call.id(), result.content());
    }
}
