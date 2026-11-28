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
 * app 模块的分层规则（core 模块的规则见 liharness-core 里的 ArchitectureTest）。
 *
 * <pre>
 *   trigger ──► cases ──► domain ◄── infrastructure
 *      └──────────────────► domain（只用它的类型）
 *   app（启动和装配）可以依赖所有层
 * </pre>
 *
 * <ul>
 *   <li>trigger 只管协议（HTTP、SSE），不能直接用 infrastructure：换一个存储实现，Controller 不应该受影响；</li>
 *   <li>cases 编排业务流程，只认 domain 的端口，不认具体实现；也不能反过来依赖 trigger。</li>
 * </ul>
 */
class AppArchitectureTest {

    private static final Pattern IMPORT = Pattern.compile("^import (?:static )?([\\w.]+);", Pattern.MULTILINE);

    @Test
    void triggerDoesNotUseInfrastructure() throws IOException {
        assertNoImports("trigger", List.of("com.pubbge.liharness.infrastructure"));
    }

    @Test
    void casesOnlyKnowDomainPorts() throws IOException {
        assertNoImports("cases", List.of("com.pubbge.liharness.infrastructure", "com.pubbge.liharness.trigger"));
    }

    private static void assertNoImports(String layer, List<String> forbidden) throws IOException {
        Path dir = sourceRoot().resolve("com/pubbge/liharness/" + layer);
        if (!Files.isDirectory(dir)) {
            return;
        }
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = IMPORT.matcher(Files.readString(f));
                while (m.find()) {
                    if (forbidden.stream().anyMatch(m.group(1)::startsWith)) {
                        violations.add(dir.relativize(f) + " → " + m.group(1));
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), layer + " 层违反依赖规则：\n" + String.join("\n", violations));
    }

    private static Path sourceRoot() {
        for (String candidate : List.of("src/main/java", "backend/liharness-app/src/main/java")) {
            if (Files.isDirectory(Path.of(candidate))) {
                return Path.of(candidate);
            }
        }
        throw new IllegalStateException("找不到 liharness-app 的源码目录");
    }
}
