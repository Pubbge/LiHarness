package com.pubbge.liharness.domain.spi.tool;

/**
 * 工具执行结果。
 *
 * @param content 交给模型的文本。要写得让模型看得懂：成功时是结果本身，失败时说清楚为什么失败、可以怎么改
 * @param error   是否失败。失败的结果同样会交给模型（标记为错误），模型通常会换个参数或换个办法再试
 */
public record ToolResult(String content, boolean error) {

    public ToolResult {
        content = content == null ? "" : content;
    }

    public static ToolResult ok(String content) {
        return new ToolResult(content, false);
    }

    public static ToolResult error(String message) {
        return new ToolResult(message, true);
    }
}
