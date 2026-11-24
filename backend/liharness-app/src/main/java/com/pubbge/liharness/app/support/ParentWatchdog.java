package com.pubbge.liharness.app.support;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 父进程看门狗：父进程（桌面外壳）不在了，就调用 onParentGone。 */
public final class ParentWatchdog implements AutoCloseable{

    private final ScheduledExecutorService scheduler;

    public ParentWatchdog(long parentPid, Duration interval, Runnable onParentGone) {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "liharness-parent-watchdog");
            t.setDaemon(true);
            return t;
        });
        long millis = interval.toMillis();
        scheduler.scheduleWithFixedDelay(() -> {
            boolean alive = ProcessHandle.of(parentPid)
                .map(ProcessHandle::isAlive)
                .orElse(false);
            if (!alive) {
                scheduler.shutdown();
                onParentGone.run();
            }
        }, millis, millis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

}
