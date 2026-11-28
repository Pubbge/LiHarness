package com.pubbge.liharness.domain.shared.cancel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CancellationTokenTest {

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

    @Test
    void lateRegistrationRunsImmediately() {
        CancellationToken token = new CancellationToken();
        token.cancel();
        AtomicInteger runs = new AtomicInteger();

        token.onCancel(runs::incrementAndGet);

        assertEquals(1, runs.get());
    }

    @Test
    void closedRegistrationDoesNotRun() {
        CancellationToken token = new CancellationToken();
        AtomicInteger runs = new AtomicInteger();
        try (CancellationToken.Registration ignored = token.onCancel(runs::incrementAndGet)) {
            // 模拟“阻塞调用正常结束”
        }
        token.cancel();
        assertEquals(0, runs.get());
    }

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

    @Test
    void throwIfCancelled() {
        CancellationToken token = new CancellationToken();
        token.throwIfCancelled(); // 未取消：不抛
        token.cancel();
        assertThrows(CancelledException.class, token::throwIfCancelled);
    }

    /** 并发压测：注册和取消同时发生，回调必须恰好执行一次 */
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
