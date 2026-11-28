package com.pubbge.liharness;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 分层规则写成测试：谁违反了，构建就失败。
 *
 * <pre>
 *   domain          ← 只依赖 JDK、Jackson 和 domain.spi；不能依赖 infrastructure，也不能依赖 Spring
 *   infrastructure  → 实现 domain 的端口；不能依赖 cases / trigger / app
 * </pre>
 *
 * <p>为什么不用 ArchUnit：它是做这件事的专业工具（deepseek-harness-java 就用它），但规则只有几条时，
 * 直接扫源码里的 import 语句就够了，少一个依赖，原理也一目了然。规则多起来以后再换成 ArchUnit。
 */
class ArchitectureTest {

    private static final Pattern IMPORT = Pattern.compile("^import (?:static )?([\\w.]+);", Pattern.MULTILINE);
    private static final String ROOT = "com/pubbge/liharness/";

    @Test
    void domainDependsOnNothingOutsideItself() throws IOException {
        assertNoImports("domain", List.of(
            "com.pubbge.liharness.infrastructure", "com.pubbge.liharness.cases",
            "com.pubbge.liharness.trigger", "com.pubbge.liharness.app", "org.springframework"));
    }

    @Test
    void infrastructureOnlyImplementsDomainPorts() throws IOException {
        assertNoImports("infrastructure", List.of(
            "com.pubbge.liharness.cases", "com.pubbge.liharness.trigger", "com.pubbge.liharness.app",
            "org.springframework"));
    }

    /** layer 目录下的所有源文件，都不能 import 以 forbidden 开头的包 */
    static void assertNoImports(String layer, List<String> forbidden) throws IOException {
        Path dir = sourceRoot().resolve(ROOT + layer);
        if (!Files.isDirectory(dir)) {
            return;
        }
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = IMPORT.matcher(Files.readString(f));
                while (m.find()) {
                    String imported = m.group(1);
                    if (forbidden.stream().anyMatch(imported::startsWith)) {
                        violations.add(dir.relativize(f) + " → " + imported);
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), layer + " 层违反依赖规则：\n" + String.join("\n", violations));
    }

    /** Maven 在模块目录下运行测试；在仓库根目录运行时也能找到 */
    static Path sourceRoot() {
        for (String candidate : List.of("src/main/java", "backend/liharness-core/src/main/java")) {
            if (Files.isDirectory(Path.of(candidate))) {
                return Path.of(candidate);
            }
        }
        throw new IllegalStateException("找不到 liharness-core 的源码目录");
    }
}
