package com.pubbge.liharness.app.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 配置目录（harness home），默认 {@code ~/.liharness}。
 *
 * <pre>
 * ~/.liharness/
 * ├── settings.yaml        模型、provider（第 05 章），权限（第 10 章）
 * ├── .credentials.yaml    API Key，权限 600（第 05 章）
 * ├── sessions/            会话记录（第 11 章）
 * ├── skills/              用户级技能（第 13 章）
 * └── mcp.json             MCP 服务器（第 14 章）
 * </pre>
 *
 * <p>为什么不放在 Tauri 给的应用数据目录（第 02 章的 {@code liharness.data-dir}）里：那个目录在
 * macOS 上是 {@code ~/Library/Application Support/<应用标识>}，用户很难找到，也不方便手改。配置是给人看、给人改的，
 * 放在家目录下一个固定的位置最直接（Claude Code 的 {@code ~/.claude}、DeepSeek Harness 的 {@code ~/.dsh} 都是这样）。
 * 应用数据目录继续放<b>运行时</b>的东西：日志、backend.json。
 *
 * <p>位置的优先级：启动参数 {@code --li-harness.home} &gt; 环境变量 {@code LIHARNESS_HOME} &gt; {@code ~/.liharness}。
 *
 * @param root 配置目录的绝对路径
 */
public record HarnessHome(Path root) {

    public HarnessHome {
        root = root.toAbsolutePath().normalize();
    }

    /**
     * @param configured 启动参数里的值，没配为空串
     * @param env        环境变量 LIHARNESS_HOME 的值，没设为 null
     */
    public static HarnessHome resolve(String configured, String env) {
        if (configured != null && !configured.isBlank()) {
            return new HarnessHome(Path.of(configured));
        }
        if (env != null && !env.isBlank()) {
            return new HarnessHome(Path.of(env));
        }
        return new HarnessHome(Path.of(System.getProperty("user.home"), ".liharness"));
    }

    /** 不存在就创建 */
    public HarnessHome ensureExists() {
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建配置目录 " + root, e);
        }
        return this;
    }

    public Path settingsFile() {
        return root.resolve("settings.yaml");
    }

    public Path credentialsFile() {
        return root.resolve(".credentials.yaml");
    }
}
