package com.pubbge.liharness.core.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CancellationTokenTest {

    /** 重复取消也只执行一次回调，且令牌保持已取消状态。 */
    @Test
    void callbackRunsOnceEvenIfCancelledTwice() {
        CancellationToken token = new CancellationToken();
        AtomicInteger runs = new AtomicInteger();
        token.onCancel(runs::incrementAndGet);

        token.cancel();
        token.cancel();

        assertTrue(token.isCancelled());
        assertEquals(1, runs.get());
    }

    /** 先取消、后注册时，回调应在注册时立即执行。 */
    @Test
    void lateRegistrationRunsImmediately() {
        CancellationToken token = new CancellationToken();
        token.cancel();
        AtomicInteger runs = new AtomicInteger();

        token.onCancel(runs::incrementAndGet);

        assertEquals(1, runs.get());
    }

    /** 正常结束时关闭注册句柄，之后取消不应再执行已注销的回调。 */
    @Test
    void closedRegistrationDoesNotRun() {
        CancellationToken token = new CancellationToken();
        AtomicInteger runs = new AtomicInteger();
        try (CancellationToken.Registration ignored = token.onCancel(runs::incrementAndGet)) {
            // 模拟阻塞调用正常结束后注销取消回调。
        }

        token.cancel();

        assertEquals(0, runs.get());
    }

    /** 某个回调抛异常时，不应阻止后续回调执行。 */
    @Test
    void failingCallbackDoesNotStopOthers() {
        CancellationToken token = new CancellationToken();
        AtomicInteger runs = new AtomicInteger();
        token.onCancel(() -> {
            throw new IllegalStateException("boom");
        });
        token.onCancel(runs::incrementAndGet);

        token.cancel();

        assertEquals(1, runs.get());
    }

    /** 未取消时检查不会抛异常；取消后检查会抛 CancelledException。 */
    @Test
    void throwIfCancelled() {
        CancellationToken token = new CancellationToken();
        token.throwIfCancelled();
        token.cancel();

        assertThrows(CancelledException.class, token::throwIfCancelled);
    }

    /** 并发检查：注册回调与取消同时发生，每轮回调都必须恰好执行一次。 */
    @Test
    void racingRegisterAndCancelRunsExactlyOnce() throws Exception {
        for (int i = 0; i < 2_000; i++) {
            CancellationToken token = new CancellationToken();
            AtomicInteger runs = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);
            Thread canceller = new Thread(() -> {
                await(start);
                token.cancel();
            });

            canceller.start();
            start.countDown();
            token.onCancel(runs::incrementAndGet);
            canceller.join();

            assertEquals(1, runs.get(), "第 " + i + " 轮");
        }

        assertFalse(Thread.currentThread().isInterrupted());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
