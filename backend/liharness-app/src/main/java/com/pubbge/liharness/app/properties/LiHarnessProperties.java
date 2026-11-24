package com.pubbge.liharness.app.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * li-harness.* 配置。
 *
 * @param dataDir   数据目录（日志、插件，第 12 章起还有数据库）
 * @param token     启动令牌，standalone 模式下由桌面外壳通过环境变量传入
 * @param parentPid 父进程（桌面外壳）的 PID；为 null 表示不启用看门狗
 */
@ConfigurationProperties(prefix = "li-harness")
public record LiHarnessProperties(String dataDir, String token, Long parentPid) {
}
