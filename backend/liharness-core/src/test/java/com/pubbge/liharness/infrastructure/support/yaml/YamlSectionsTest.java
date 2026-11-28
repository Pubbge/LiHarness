package com.pubbge.liharness.infrastructure.support.yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class YamlSectionsTest {

    private static final String FILE = """
        # 我的设置
        defaultModel: mock/mock   # 先用 Mock

        # 公司网关
        providers:
          gw:
            baseUrl: https://gw.example/v1
        """;

    @Test
    void replacesOnlyTheChosenSectionAndKeepsComments() {
        String out = YamlSections.replace(FILE, "defaultModel", "deepseek/deepseek-v4-flash");
        assertEquals("""
            # 我的设置
            defaultModel: deepseek/deepseek-v4-flash

            # 公司网关
            providers:
              gw:
                baseUrl: https://gw.example/v1
            """, out);
    }

    @Test
    void addsMissingSectionAtTheEndAndRemovesWithNull() {
        String added = YamlSections.replace("defaultModel: mock/mock\n", "permissions", Map.of("allow", List.of("read_file")));
        assertEquals("""
            defaultModel: mock/mock
            permissions:
              allow:
                - read_file
            """, added);
        assertEquals("defaultModel: mock/mock\n", YamlSections.replace(added, "permissions", null));
        assertEquals("defaultModel: x/y\n", YamlSections.replace("", "defaultModel", "x/y"));
    }
}
