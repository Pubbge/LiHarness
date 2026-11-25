package com.pubbge.liharness.app.lifecycle;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/** 统一的关闭入口：关闭接口和看门狗都调用它。 */
@Component
public class ShutdownCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ShutdownCoordinator.class);

    private final ConfigurableApplicationContext context;
    private final AtomicBoolean requested = new AtomicBoolean(false);

    public ShutdownCoordinator(ConfigurableApplicationContext context) {
        this.context = context;
    }

    public void requestShutdown(String reason) {
        if (!requested.compareAndSet(false, true)) {
            return; // 已经在关闭中，忽略重复请求
        }
        log.info("开始关闭：{}", reason);
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(200); // 让当前的 HTTP 响应先发出去
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            int code = SpringApplication.exit(context, () -> 0);
            System.exit(code);
        }, "li-harness-shutdown");
        thread.start();
    }

}
