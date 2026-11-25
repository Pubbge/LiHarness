package com.pubbge.liharness.app.lifecycle;

import com.pubbge.liharness.app.config.LiHarnessProperties;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class ParentWatchdogStarter implements ApplicationRunner, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ParentWatchdogStarter.class);

    private final LiHarnessProperties properties;
    private final ShutdownCoordinator shutdown;
    private ParentWatchdog watchdog;

    public ParentWatchdogStarter(LiHarnessProperties properties, ShutdownCoordinator shutdown) {
        this.properties = properties;
        this.shutdown = shutdown;
    }

    @Override
    public void run(ApplicationArguments args) {
        Long pid = properties.parentPid();
        if (pid == null) {
            log.info("未设置父进程 PID，看门狗不启用（开发模式）");
            return;
        }
        log.info("看门狗已启动，监视父进程 {}", pid);
        watchdog = new ParentWatchdog(pid, Duration.ofSeconds(2),
            () -> shutdown.requestShutdown("父进程 " + pid + " 已退出"));
    }

    @Override
    public void destroy() {
        if (watchdog != null) {
            watchdog.close();
        }
    }
}
