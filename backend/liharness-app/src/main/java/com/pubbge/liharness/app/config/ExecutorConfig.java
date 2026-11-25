package com.pubbge.liharness.app.config;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ExecutorConfig {

    /** 同一时刻最多执行的回合数 */
    static final int MAX_CONCURRENT_TURNS = 8;
    /** 8 个都在忙时，最多再排队多少个 */
    static final int MAX_QUEUED_TURNS = 32;

    /**
     * 执行 Agent 回合的线程池。
     * 8 个以内立即执行；再多的排队（最多 32 个）；队列也满了就拒绝（抛 RejectedExecutionException）。
     */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService turnExecutor() {
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "li-harness-turn-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
            MAX_CONCURRENT_TURNS, MAX_CONCURRENT_TURNS, // core = max：先执行，满了才排队
            60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(MAX_QUEUED_TURNS),
            factory,
            new ThreadPoolExecutor.AbortPolicy());
        pool.allowCoreThreadTimeOut(true); // 空闲 60 秒后，核心线程也回收
        return pool;
    }

}
