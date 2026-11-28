package com.pubbge.liharness.domain.spi.tool;

/**
 * 可预期的工具错误，消息会原样交给模型。
 *
 * <p>消息要写给模型看：说清楚错在哪、应该怎么改。比如
 * “文件不存在：src/Main.java。可以先用 list_dir 看看目录里有什么”，
 * 比 “FileNotFoundException” 有用得多。
 */
public class ToolException extends Exception {

    private static final long serialVersionUID = 1L;

    public ToolException(String message) {
        super(message);
    }

    public ToolException(String message, Throwable cause) {
        super(message, cause);
    }
}
