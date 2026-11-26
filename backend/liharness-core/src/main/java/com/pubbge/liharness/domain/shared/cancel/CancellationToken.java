package com.pubbge.liharness.domain.shared.cancel;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 取消令牌：一个回合一个。用户点“停止”时调用 {@link #cancel()}，
 * 正在进行的模型调用、工具执行都应尽快停下。
 *
 * <p>两种用法：
 * <ul>
 *   <li>轮询：循环里调用 {@link #throwIfCancelled()}，适合“一段一段干活”的代码（比如逐块读流）。</li>
 *   <li>回调：{@link #onCancel(Runnable)} 注册一个动作，取消时立即执行，适合“卡在阻塞调用里”的代码
 *       （比如关闭 HTTP 连接、杀掉子进程，让阻塞调用立刻返回）。</li>
 * </ul>
 *
 * <p>线程安全：cancel() 通常在 HTTP 请求线程上调用，而回合跑在回合线程池里。
 */
public final class CancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final List<Runnable> callbacks = new CopyOnWriteArrayList<>();

    /** 请求取消。可以重复调用，只有第一次生效。 */
    public void cancel() {
        // compareAndSet 保证多个线程同时 cancel() 时，只有一个线程去执行回调
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        for (Runnable callback : callbacks) {
            // 谁先从列表里 remove 成功，谁负责执行，保证每个回调“恰好执行一次”（见 onCancel 的注释）
            if (callbacks.remove(callback)) {
                runQuietly(callback);
            }
        }
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /** 已取消就抛 {@link CancelledException}，否则什么也不做。 */
    public void throwIfCancelled() {
        if (cancelled.get()) {
            throw new CancelledException();
        }
    }

    /**
     * 注册取消回调。如果注册时已经取消了，回调会在当前线程立即执行。
     *
     * <p>为什么要“先 add 再检查”：如果反过来先检查再 add，在“检查完、还没 add”的瞬间另一个线程
     * 调用了 cancel()，回调就永远不会执行了。先 add 再检查，配合“谁 remove 成功谁执行”，
     * 两个线程不管怎么交错，回调都恰好执行一次。
     *
     * @return 注销句柄。阻塞调用正常结束后要关掉它，否则回调会一直挂在令牌上
     */
    public Registration onCancel(Runnable callback) {
        callbacks.add(callback);
        if (cancelled.get() && callbacks.remove(callback)) {
            runQuietly(callback);
        }
        return () -> callbacks.remove(callback);
    }

    /** 一个永远不会被取消的令牌，测试和“不需要取消”的场景用 */
    public static CancellationToken none() {
        return new CancellationToken();
    }

    private static void runQuietly(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException ignored) {
            // 一个回调失败不能影响其他回调，更不能让 cancel() 的调用方收到异常
        }
    }

    /** 注销句柄。实现 AutoCloseable 以便配合 try-with-resources 使用，close() 不抛受检异常。 */
    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }
}
