package com.pubbge.liharness.app.config;

import com.pubbge.liharness.domain.llm.service.LlmProviderRegistry;
import com.pubbge.liharness.domain.settings.adapter.repository.ICredentialRepository;
import com.pubbge.liharness.domain.settings.adapter.repository.ISettingsRepository;
import com.pubbge.liharness.infrastructure.adapter.llm.mock.MockAdapterFactory;
import com.pubbge.liharness.infrastructure.adapter.llm.openai.OpenAiAdapterFactory;
import com.pubbge.liharness.infrastructure.adapter.repository.YamlCredentialRepository;
import com.pubbge.liharness.infrastructure.adapter.repository.YamlSettingsRepository;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 模型相关的装配。
 *
 * <p>为什么在 app 层 new：domain 和 infrastructure 不认识 Spring（类上不能加 @Component），
 * 由这里把具体的实现作为领域的接口交给 Spring 管理。
 *
 * <p>第 05 章起，用哪个模型不再写死：{@link LlmProviderRegistry} 按 {@code ~/.liharness/settings.yaml} 决定，
 * 每次请求都重新读，改了不用重启。这里决定“设置和 Key 从哪读”“支持哪些协议”。
 */
@Configuration
public class LlmConfig {

    @Bean
    public HarnessHome harnessHome(@Value("${li-harness.home:}") String configured) {
        return HarnessHome.resolve(configured, System.getenv("LIHARNESS_HOME")).ensureExists();
    }

    /**
     * 第一次启动时写进 settings.yaml 的内容：默认就有 DeepSeek 这一家，用户只要在设置页填上 API Key 就能用。
     * 别家的服务商在设置页里点“添加模型提供商”加。
     *
     * <p>为什么写成文件，而不是在代码里“隐藏地内置”一家：界面上看到的、文件里写的、程序用的，三者永远一致。
     * 用户删掉 DeepSeek，它就真的没了，不会因为代码里还藏着一份又冒出来。
     */
    static final String DEFAULT_SETTINGS = """
        # LiHarness 的设置。可以在应用的“设置 → 模型”里改，也可以直接编辑这个文件，保存后下一次提问就生效。
        # API Key 不写在这里，保存在同一个目录的 .credentials.yaml（只有你自己能读）。

        # 当前用的模型：服务商标识/模型名
        defaultModel: deepseek/deepseek-v4-flash

        # 模型服务商。baseUrl 写到 /chat/completions 前面那一段就行，后面由程序拼上
        providers:
          deepseek:
            name: DeepSeek
            baseUrl: https://api.deepseek.com
            models:
              - deepseek-v4-flash
              - deepseek-v4-pro
        """;

    @Bean
    public ISettingsRepository settingsRepository(HarnessHome home) {
        YamlSettingsRepository repo = new YamlSettingsRepository(home.settingsFile());
        repo.createIfMissing(DEFAULT_SETTINGS);
        return repo;
    }

    @Bean
    public ICredentialRepository credentialRepository(HarnessHome home) {
        return new YamlCredentialRepository(home.credentialsFile());
    }

    /** 注册支持的协议。第二阶段要支持 Anthropic 原生协议，就在这个列表里加一个工厂 */
    @Bean
    public LlmProviderRegistry llmProviderRegistry(ISettingsRepository settings, ICredentialRepository credentials) {
        return new LlmProviderRegistry(settings, credentials, System::getenv,
            List.of(new MockAdapterFactory(), new OpenAiAdapterFactory()));
    }
}
