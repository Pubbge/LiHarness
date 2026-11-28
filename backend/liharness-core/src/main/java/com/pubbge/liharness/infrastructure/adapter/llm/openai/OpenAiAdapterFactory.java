package com.pubbge.liharness.infrastructure.adapter.llm.openai;

import com.pubbge.liharness.domain.llm.adapter.port.ILlmAdapterFactory;
import com.pubbge.liharness.domain.llm.adapter.port.LlmChatModelAdapter;
import com.pubbge.liharness.domain.llm.provider.ModelProfile;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 协议 {@code openai-completions}：OpenAI Chat Completions 以及兼容它的服务
 * （DeepSeek、Kimi、GLM、通义千问、Ollama、vLLM……）。
 *
 * <p>所有模型共用一个 {@link HttpClient}：它自带连接池，每次 new 一个会丢掉已经建好的连接。
 */
public final class OpenAiAdapterFactory implements ILlmAdapterFactory {

    private final HttpClient http;

    public OpenAiAdapterFactory() {
        this(HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            // 模型服务基本都支持 HTTP/2；不支持时 JDK 会自动降级到 1.1
            .version(HttpClient.Version.HTTP_2)
            .build());
    }

    public OpenAiAdapterFactory(HttpClient http) {
        this.http = http;
    }

    @Override
    public String protocol() {
        return "openai-completions";
    }

    @Override
    public LlmChatModelAdapter create(ModelProfile profile, String apiKey) {
        OpenAiConfig config = new OpenAiConfig(profile.baseUrl(), apiKey, profile.model(), OpenAiConfig.DEFAULT_IDLE_TIMEOUT);
        return new OpenAiCompatibleAdapter(config, http);
    }
}
