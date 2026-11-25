package com.pubbge.liharness.app.lifecycle;

import java.time.Instant;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class SystemController {

    private final ShutdownCoordinator shutdown;
    private final Instant startedAt = Instant.now();

    public SystemController(ShutdownCoordinator shutdown) {
        this.shutdown = shutdown;
    }

    /** 健康检查：桌面外壳靠它判断后端是否就绪。 */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
            "status", "UP",
            "pid", ProcessHandle.current().pid(),
            "startedAt", startedAt.toString());
    }

    /** 桌面外壳退出前调用：优雅关闭。 */
    @PostMapping("/admin/shutdown")
    public ResponseEntity<Map<String, String>> shutdown() {
        shutdown.requestShutdown("收到 /api/admin/shutdown");
        return ResponseEntity.accepted().body(Map.of("status", "shutting_down"));
    }

}
