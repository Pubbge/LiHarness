package com.pubbge.liharness.domain.llm.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pubbge.liharness.domain.llm.adapter.port.ILlmAdapterFactory;
import com.pubbge.liharness.domain.llm.adapter.port.LlmChatModelAdapter;
import com.pubbge.liharness.domain.llm.adapter.port.LlmStream;
import com.pubbge.liharness.domain.llm.error.LlmException;
import com.pubbge.liharness.domain.llm.exchange.LlmRequest;
import com.pubbge.liharness.domain.llm.provider.ModelConfig;
import com.pubbge.liharness.domain.llm.provider.ModelProfile;
import com.pubbge.liharness.domain.llm.provider.ModelRef;
import com.pubbge.liharness.domain.llm.provider.ProviderConfig;
import com.pubbge.liharness.domain.llm.service.LlmProviderRegistry.KeySource;
import com.pubbge.liharness.domain.settings.adapter.repository.ICredentialRepository;
import com.pubbge.liharness.domain.settings.adapter.repository.ISettingsRepository;
import com.pubbge.liharness.domain.settings.model.valobj.Settings;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** 注册表的规则：当前模型怎么定、模型必须在服务商的列表里、找 Key 的顺序、没配好时的报错。不碰文件、不联网 */
class LlmProviderRegistryTest {

    /** 记下创建时拿到的配置和 Key 的假适配器工厂 */
    private static final class FakeAdapter implements ILlmAdapterFactory {
        final String protocol;
        ModelProfile lastProfile;
        String lastKey;

        FakeAdapter(String protocol) {
            this.protocol = protocol;
        }

        @Override
        public String protocol() {
            return protocol;
        }

        @Override
        public LlmChatModelAdapter create(ModelProfile profile, String apiKey) {
            lastProfile = profile;
            lastKey = apiKey;
            return new LlmChatModelAdapter() {
                @Override
                public String id() {
                    return protocol;
                }

                @Override
                public LlmStream stream(LlmRequest request) {
                    throw new UnsupportedOperationException();
                }
            };
        }
    }

    private Settings settings = Settings.empty();
    private final Map<String, String> saved = new HashMap<>();
    private final Map<String, String> env = new HashMap<>();
    private final FakeAdapter openAi = new FakeAdapter("openai-completions");
    private final FakeAdapter mock = new FakeAdapter("mock");
    private final LlmProviderRegistry registry = new LlmProviderRegistry(settingsOf(), credentialsOf(), env::get,
        List.of(mock, openAi));

    private static ProviderConfig deepseek(String... models) {
        return new ProviderConfig("deepseek", "DeepSeek", null, "https://api.deepseek.com", null, true,
            List.of(models).stream().map(ModelConfig::of).toList());
    }

    @Test
    void withNoProviderTheCurrentModelIsMock() throws Exception {
        assertEquals(LlmProviderRegistry.MOCK, registry.defaultModel());
        registry.openDefault();
        assertEquals("mock", mock.lastProfile.protocol());
        assertTrue(registry.providers().isEmpty()); // mock 不算“你添加的服务商”
    }

    @Test
    void withoutDefaultModelTheFirstProvidersFirstModelIsUsed() throws Exception {
        settings = Settings.empty().withProvider(deepseek("deepseek-v4-flash", "deepseek-v4-pro"));
        assertEquals(new ModelRef("deepseek", "deepseek-v4-flash"), registry.defaultModel());

        settings = settings.withDefaultModel("deepseek/deepseek-v4-pro");
        assertEquals(new ModelRef("deepseek", "deepseek-v4-pro"), registry.defaultModel());
    }

    @Test
    void modelMustBeListedUnderItsProvider() throws Exception {
        settings = Settings.empty().withProvider(deepseek("deepseek-v4-flash"));
        ModelProfile p = registry.resolve(ModelRef.parse("deepseek/deepseek-v4-flash"));
        assertEquals("https://api.deepseek.com", p.baseUrl());
        assertEquals("openai-completions", p.protocol()); // 不写协议就是 openai-completions
        assertEquals(ModelProfile.DEFAULT_CONTEXT_WINDOW, p.contextWindow());
        assertEquals("DeepSeek · deepseek-v4-flash", p.displayName());

        LlmException e = assertThrows(LlmException.class, () -> registry.resolve(ModelRef.parse("deepseek/nope")));
        assertEquals(LlmException.Kind.NOT_CONFIGURED, e.kind());
        assertTrue(e.getMessage().contains("[deepseek-v4-flash]"), e.getMessage());

        e = assertThrows(LlmException.class, () -> registry.resolve(ModelRef.parse("qwen/qwen-max")));
        assertTrue(e.getMessage().contains("先在设置页"), e.getMessage());
    }

    @Test
    void savedKeyWinsOverEnvAndMissingKeyExplainsWhatToDo() throws Exception {
        settings = Settings.empty().withProvider(new ProviderConfig("deepseek", "DeepSeek", null,
            "https://api.deepseek.com", "DEEPSEEK_API_KEY", true, List.of(ModelConfig.of("deepseek-v4-flash"))));
        ProviderConfig p = registry.provider("deepseek");
        ModelProfile profile = registry.resolve(registry.defaultModel());

        LlmException e = assertThrows(LlmException.class, () -> registry.open(profile));
        assertTrue(e.getMessage().contains("还没有 API Key"), e.getMessage());
        assertEquals(KeySource.MISSING, registry.keySource(p));

        env.put("DEEPSEEK_API_KEY", "from-env");
        registry.open(profile);
        assertEquals("from-env", openAi.lastKey);
        assertEquals(KeySource.ENV, registry.keySource(p));

        saved.put("deepseek", "from-file");
        registry.open(profile);
        assertEquals("from-file", openAi.lastKey);
        assertEquals(KeySource.SAVED, registry.keySource(p));
    }

    @Test
    void localProviderNeedsNoKeyAndBadUrlIsRejected() throws Exception {
        settings = Settings.empty().withProvider(new ProviderConfig("ollama", "Ollama", null,
            "http://localhost:11434/v1", null, false, List.of(ModelConfig.of("qwen3:8b"))));
        registry.open(registry.resolve(ModelRef.parse("ollama/qwen3:8b")));
        assertEquals(null, openAi.lastKey);
        assertEquals(KeySource.NOT_REQUIRED, registry.keySource(registry.provider("ollama")));

        settings = Settings.empty().withProvider(new ProviderConfig("bad", null, null, "ftp://x", null, true,
            List.of(ModelConfig.of("m"))));
        LlmException e = assertThrows(LlmException.class, () -> registry.resolve(ModelRef.parse("bad/m")));
        assertTrue(e.getMessage().contains("http://"), e.getMessage());
    }

    @Test
    void brokenSettingsFileBecomesNotConfigured() {
        LlmProviderRegistry broken = new LlmProviderRegistry(new ISettingsRepository() {
            @Override
            public Settings current() {
                throw new IllegalStateException("settings.yaml 写错了：第 3 行");
            }

            @Override
            public Settings update(UnaryOperator<Settings> change) {
                throw new UnsupportedOperationException();
            }
        }, credentialsOf(), env::get, List.of(mock, openAi));
        LlmException e = assertThrows(LlmException.class, broken::defaultModel);
        assertEquals(LlmException.Kind.NOT_CONFIGURED, e.kind());
        assertTrue(e.getMessage().contains("第 3 行"));
    }

    private ISettingsRepository settingsOf() {
        return new ISettingsRepository() {
            @Override
            public Settings current() {
                return settings;
            }

            @Override
            public Settings update(UnaryOperator<Settings> change) {
                settings = change.apply(settings);
                return settings;
            }
        };
    }

    private ICredentialRepository credentialsOf() {
        return new ICredentialRepository() {
            @Override
            public Optional<String> find(String name) {
                return Optional.ofNullable(saved.get(name));
            }

            @Override
            public void save(String name, String apiKey) {
                saved.put(name, apiKey);
            }

            @Override
            public void remove(String name) {
                saved.remove(name);
            }

            @Override
            public Collection<String> values() {
                return saved.values();
            }
        };
    }
}
