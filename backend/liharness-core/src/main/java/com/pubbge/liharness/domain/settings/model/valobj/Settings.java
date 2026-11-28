package com.pubbge.liharness.domain.settings.model.valobj;

import com.pubbge.liharness.domain.llm.provider.ProviderConfig;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户设置，保存在 {@code ~/.liharness/settings.yaml}。
 *
 * <pre>
 * defaultModel: deepseek/deepseek-v4-flash   # 当前用的模型：服务商id/模型id
 * providers:                                 # 你添加的服务商
 *   deepseek:
 *     name: DeepSeek
 *     baseUrl: https://api.deepseek.com
 *     models:
 *       - deepseek-v4-flash
 *       - deepseek-v4-pro
 * </pre>
 *
 * @param defaultModel 当前用的模型；为空时用第一个服务商的第一个模型，一个服务商都没有就用 Mock
 * @param providers    你添加的服务商，key 是服务商的 id，顺序和文件里一样
 */
public record Settings(String defaultModel, Map<String, ProviderConfig> providers) {

    public Settings {
        defaultModel = defaultModel == null || defaultModel.isBlank() ? null : defaultModel.strip();
        providers = providers == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(providers));
    }

    /** 还没有 settings.yaml 时的设置 */
    public static Settings empty() {
        return new Settings(null, Map.of());
    }

    public Settings withDefaultModel(String model) {
        return new Settings(model, providers);
    }

    /** 添加或替换一个服务商。替换时位置不变，新加的放在最后 */
    public Settings withProvider(ProviderConfig provider) {
        Map<String, ProviderConfig> m = new LinkedHashMap<>(providers);
        m.put(provider.id(), provider);
        return new Settings(defaultModel, m);
    }

    public Settings withoutProvider(String id) {
        Map<String, ProviderConfig> m = new LinkedHashMap<>(providers);
        m.remove(id);
        return new Settings(defaultModel, m);
    }
}
