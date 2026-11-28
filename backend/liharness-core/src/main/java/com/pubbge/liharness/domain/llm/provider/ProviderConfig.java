package com.pubbge.liharness.domain.llm.provider;

import java.util.List;

/**
 * 一个模型服务商（provider）：连哪个地址、用什么协议、有哪些模型。
 *
 * <p>你在设置页里添加的、或者在 {@code ~/.liharness/settings.yaml} 的 {@code providers:} 下写的，每一项就是一个
 * ProviderConfig。模型绑在服务商下面：选模型时写成 {@code 服务商id/模型id}。
 *
 * <p>这里<b>没有 Key 本身</b>。Key 保存在 {@code ~/.liharness/.credentials.yaml}，名字就是服务商的 id；
 * 也可以用 {@code apiKeyEnv} 指定一个环境变量。settings.yaml 因此可以放心给别人看。
 *
 * @param id          服务商的标识（小写字母、数字、短横线），写在模型名前面
 * @param name        界面上显示的名字
 * @param protocol    接口格式，决定用哪个适配器。不写就是 {@code openai-completions}（DeepSeek、通义、Kimi 等都是）
 * @param baseUrl     接口地址，请求发到 baseUrl + "/chat/completions"
 * @param apiKeyEnv   可选：放 Key 的环境变量名
 * @param requiresKey 是否需要 Key。本机的 Ollama 不需要
 * @param models      这个服务商下能用的模型，至少一个
 */
public record ProviderConfig(
        String id,
        String name,
        String protocol,
        String baseUrl,
        String apiKeyEnv,
        boolean requiresKey,
        List<ModelConfig> models) {

    public static final String OPENAI_COMPLETIONS = "openai-completions";

    public ProviderConfig {
        id = id == null ? "" : id.strip();
        if (!id.matches("[a-z0-9][a-z0-9-]{0,31}")) {
            throw new IllegalArgumentException("服务商的标识只能用小写字母、数字和短横线：" + id);
        }
        name = name == null || name.isBlank() ? id : name.strip();
        protocol = protocol == null || protocol.isBlank() ? OPENAI_COMPLETIONS : protocol.strip();
        baseUrl = baseUrl == null || baseUrl.isBlank() ? null : baseUrl.strip();
        apiKeyEnv = apiKeyEnv == null || apiKeyEnv.isBlank() ? null : apiKeyEnv.strip();
        models = models == null ? List.of() : List.copyOf(models);
    }

    /** 按 id 找模型 */
    public java.util.Optional<ModelConfig> model(String modelId) {
        return models.stream().filter(m -> m.id().equals(modelId)).findFirst();
    }

    public List<String> modelIds() {
        return models.stream().map(ModelConfig::id).toList();
    }
}
