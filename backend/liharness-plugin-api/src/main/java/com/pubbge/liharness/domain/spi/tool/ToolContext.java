package com.pubbge.liharness.domain.spi.tool;

import java.nio.file.Path;

/**
 * 一次工具调用的上下文，由 Harness 提供给工具。
 *
 * <p>工具需要的外部信息都从这里拿，而不是从全局变量或 Spring 容器里拿。这样同一个工具
 * 在主代理、子代理（第 13 章）、单元测试里都能原样运行。
 */
public interface ToolContext {

    String sessionId();

    String turnId();

    /** 当前工具调用的 id（模型生成的那个），用来关联进度和结果 */
    String toolCallId();

    /** 工作区根目录：文件类工具只能访问它里面的文件（第 07 章） */
    Path workspace();

    /** 用户是否已经要求停止 */
    boolean isCancelled();

    /**
     * 注册取消时要执行的动作，比如杀掉子进程、关闭网络连接。
     * 如果已经取消了，动作会立即执行。工具正常结束时应关闭返回的句柄。
     */
    Registration onCancel(Runnable action);

    /** 报告进度（比如命令输出的最新一行），会显示在界面上的工具卡片里。没有界面时是空操作 */
    void reportProgress(String message);

    /** 取消回调的注销句柄 */
    @FunctionalInterface
    interface Registration extends AutoCloseable {
        @Override
        void close();
    }
}
