package com.pubbge.liharness.core.common;

/**
 * 回合被用户取消。
 *
 * <p>它是非受检异常：取消可能发生在调用栈的任何深处（模型调用、工具执行、等待审批），
 * 如果做成受检异常，每一层方法签名都得声明它，噪音太大。Agent Loop 在最外层统一捕获。
 */
public final class CancelledException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CancelledException() {
        // 取消是正常的控制流，不需要堆栈（填充堆栈很贵，日志里也没用）
        super("已取消", null, false, false);
    }
}
