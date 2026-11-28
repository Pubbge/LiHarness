package com.pubbge.liharness.infrastructure.adapter.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pubbge.liharness.domain.llm.provider.ProviderConfig;
import com.pubbge.liharness.domain.settings.model.valobj.Settings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class YamlSettingsRepositoryTest {

    @TempDir
    Path home;

    @Test
    void missingFileMeansEmptySettings() {
        Settings s = new YamlSettingsRepository(home.resolve("settings.yaml")).current();
        assertNull(s.defaultModel());
        assertTrue(s.providers().isEmpty());
    }

    @Test
    void readsProvidersAndPicksUpChangesWithoutRestart() throws Exception {
        Path file = home.resolve("settings.yaml");
        Files.writeString(file, """
            # 注释可以随便写
            defaultModel: my-gateway/qwen-max
            providers:
              my-gateway:
                baseURL: https://gateway.example/v1     # dsh 的写法也认
                apiKeyEnv: GATEWAY_API_KEY
                models:
                  - qwen-plus                           # 只写模型名
                  - id: qwen-max                        # 带参数
                    contextWindow: 32000
                    maxOutputTokens: 4096
            """);
        YamlSettingsRepository repo = new YamlSettingsRepository(file);
        Settings s = repo.current();
        assertEquals("my-gateway/qwen-max", s.defaultModel());
        ProviderConfig p = s.providers().get("my-gateway");
        assertEquals("https://gateway.example/v1", p.baseUrl());
        assertEquals("my-gateway", p.name());                     // 不写名称就用标识
        assertEquals("openai-completions", p.protocol());          // 不写协议就是 openai-completions
        assertTrue(p.requiresKey());                               // 不写就是需要 Key
        assertEquals(java.util.List.of("qwen-plus", "qwen-max"), p.modelIds());
        assertEquals(Integer.valueOf(32000), p.models().get(1).contextWindow());

        Files.writeString(file, "defaultModel: deepseek/deepseek-v4-pro\n");
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(5))); // 防止同一毫秒内写两次
        assertEquals("deepseek/deepseek-v4-pro", repo.current().defaultModel());
    }

    @Test
    void typosAndBrokenYamlAreReportedWithTheFileName() throws Exception {
        Path file = home.resolve("settings.yaml");
        Files.writeString(file, "defaultModle: deepseek/x\n");
        IllegalStateException typo = assertThrows(IllegalStateException.class, () -> new YamlSettingsRepository(file).current());
        assertTrue(typo.getMessage().contains("settings.yaml"));
        assertTrue(typo.getMessage().contains("defaultModle"));

        Files.writeString(file, "providers: [\n");
        assertThrows(IllegalStateException.class, () -> new YamlSettingsRepository(file).current());
    }

    @Test
    void yamlTagsCannotCreateArbitraryObjects() throws Exception {
        Path file = home.resolve("settings.yaml");
        Files.writeString(file, "defaultModel: !!java.io.File [\"/tmp\"]\n");
        assertThrows(IllegalStateException.class, () -> new YamlSettingsRepository(file).current());
    }

    @Test
    void credentialsFileMapsNamesToKeys() throws Exception {
        Path file = home.resolve(".credentials.yaml");
        Files.writeString(file, "deepseek: sk-123\nbroken:\n  nested: no\n");
        YamlCredentialRepository repo = new YamlCredentialRepository(file);
        assertEquals("sk-123", repo.find("deepseek").orElseThrow());
        assertTrue(repo.find("broken").isEmpty());
        assertTrue(new YamlCredentialRepository(home.resolve("none.yaml")).find("deepseek").isEmpty());
    }

    @Test
    void updateKeepsHandWrittenPartsAndWritesModelsCompactly() throws Exception {
        Path file = home.resolve("settings.yaml");
        Files.writeString(file, """
            # 我的设置，别删
            defaultModel: mock/mock
            """);
        YamlSettingsRepository repo = new YamlSettingsRepository(file);
        repo.update(s -> s
            .withProvider(new ProviderConfig("deepseek", "DeepSeek", null, "https://api.deepseek.com", null, true,
                java.util.List.of(com.pubbge.liharness.domain.llm.provider.ModelConfig.of("deepseek-v4-flash"))))
            .withDefaultModel("deepseek/deepseek-v4-flash"));
        String text = Files.readString(file);
        assertTrue(text.startsWith("# 我的设置，别删\n"), text);
        assertTrue(text.contains("defaultModel: deepseek/deepseek-v4-flash"), text);
        assertTrue(text.contains("    - deepseek-v4-flash"), text);       // 只有名字的模型写成一行
        assertTrue(!text.contains("protocol"), text);                     // 默认值不写出来
        assertEquals("DeepSeek", repo.current().providers().get("deepseek").name());

        repo.update(s -> s.withoutProvider("deepseek").withDefaultModel(null));
        text = Files.readString(file);
        assertTrue(!text.contains("providers") && !text.contains("defaultModel"), text);
        assertTrue(text.startsWith("# 我的设置，别删"), text);
    }

    @Test
    void savedCredentialsAreOwnerOnly() throws Exception {
        Path file = home.resolve(".credentials.yaml");
        YamlCredentialRepository repo = new YamlCredentialRepository(file);
        repo.save("deepseek", " sk-abc ");
        assertEquals("sk-abc", repo.find("deepseek").orElseThrow());
        if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        }
        repo.remove("deepseek");
        assertTrue(repo.find("deepseek").isEmpty());
    }

    @Test
    void initialSettingsAreWrittenOnlyWhenTheFileDoesNotExist() throws Exception {
        Path file = home.resolve("settings.yaml");
        YamlSettingsRepository repo = new YamlSettingsRepository(file);
        repo.createIfMissing("defaultModel: deepseek/deepseek-v4-flash\n");
        assertEquals("deepseek/deepseek-v4-flash", repo.current().defaultModel());

        Files.writeString(file, "defaultModel: mock/mock\n"); // 用户改过以后
        repo.createIfMissing("defaultModel: deepseek/deepseek-v4-flash\n");
        assertEquals("defaultModel: mock/mock\n", Files.readString(file)); // 绝不覆盖
    }
}
