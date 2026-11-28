package com.pubbge.liharness.infrastructure.adapter.repository;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pubbge.liharness.domain.llm.provider.ModelConfig;
import com.pubbge.liharness.domain.llm.provider.ProviderConfig;
import com.pubbge.liharness.domain.settings.adapter.repository.ISettingsRepository;
import com.pubbge.liharness.domain.settings.model.valobj.Settings;
import com.pubbge.liharness.domain.shared.json.Json;
import com.pubbge.liharness.infrastructure.support.AtomicFiles;
import com.pubbge.liharness.infrastructure.support.yaml.YamlFile;
import com.pubbge.liharness.infrastructure.support.yaml.YamlSections;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * {@link ISettingsRepository} 的 YAML 实现：读写 {@code ~/.liharness/settings.yaml}。
 *
 * <pre>
 * defaultModel: deepseek/deepseek-v4-flash
 * providers:
 *   deepseek:
 *     name: DeepSeek
 *     baseUrl: https://api.deepseek.com
 *     models:
 *       - deepseek-v4-flash            # 只写模型名
 *       - id: deepseek-v4-pro          # 或者写成带参数的形式
 *         contextWindow: 128000
 * </pre>
 *
 * <p>读：不认识的字段直接报错（手写配置最常见的错误就是字段名拼错，悄悄忽略的话用户会以为配置生效了）。
 *
 * <p>写：只替换改动的顶层段落（{@link YamlSections}），你手写的注释和其他内容原样保留；写之前先校验，
 * 写入是原子的（{@link AtomicFiles}）。设置页和编辑器改的是同一个文件。
 */
public final class YamlSettingsRepository implements ISettingsRepository {

    /** 文件的格式（和你手写的 YAML 一一对应） */
    record SettingsFile(String defaultModel, Map<String, ProviderEntry> providers) {
    }

    record ProviderEntry(
            String name,
            String protocol,
            @JsonAlias("baseURL") String baseUrl,
            String apiKeyEnv,
            Boolean requiresKey,
            List<Object> models) {
    }

    record ModelEntry(String id, String name, Integer contextWindow, Integer maxOutputTokens) {
    }

    private static final ObjectMapper STRICT = Json.mapper().copy()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    private final YamlFile<Settings> file;

    public YamlSettingsRepository(Path settingsFile) {
        this.file = new YamlFile<>(settingsFile, YamlSettingsRepository::convert, Settings.empty());
    }

    @Override
    public Settings current() {
        return file.get();
    }

    /**
     * 文件还不存在时，写入一份初始设置（第一次启动时调用）。已经存在就什么都不做，绝不覆盖用户的文件。
     *
     * @param text 初始内容，写之前先校验能读出来
     */
    public synchronized void createIfMissing(String text) {
        Path path = file.path();
        if (Files.exists(path)) {
            return;
        }
        Map<String, Object> check = YamlFile.parseMap(text);
        convert(check == null ? Map.of() : check);
        try {
            AtomicFiles.write(path, text, false);
        } catch (IOException e) {
            throw new IllegalStateException("写入 " + path + " 失败：" + e.getMessage(), e);
        }
    }

    @Override
    public synchronized Settings update(UnaryOperator<Settings> change) {
        Settings before = current();
        Settings after = change.apply(before);
        Path path = file.path();
        try {
            String text = Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
            if (!Objects.equals(before.defaultModel(), after.defaultModel())) {
                text = YamlSections.replace(text, "defaultModel", after.defaultModel());
            }
            if (!before.providers().equals(after.providers())) {
                text = YamlSections.replace(text, "providers", after.providers().isEmpty() ? null : toYaml(after.providers()));
            }
            Map<String, Object> check = YamlFile.parseMap(text); // 写之前再解析一遍：保证写进去的文件下次一定读得出来
            convert(check == null ? Map.of() : check);
            AtomicFiles.write(path, text, false);
        } catch (IOException e) {
            throw new IllegalStateException("写入 " + path + " 失败：" + e.getMessage(), e);
        }
        return current();
    }

    /** 服务商 → YAML。只写和默认值不同的字段，模型只有名字时写成一行字符串，和手写的一样简洁 */
    static Map<String, Object> toYaml(Map<String, ProviderConfig> providers) {
        Map<String, Object> out = new LinkedHashMap<>();
        providers.forEach((id, p) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            if (!p.name().equals(id)) {
                m.put("name", p.name());
            }
            if (!ProviderConfig.OPENAI_COMPLETIONS.equals(p.protocol())) {
                m.put("protocol", p.protocol());
            }
            putIfNotNull(m, "baseUrl", p.baseUrl());
            putIfNotNull(m, "apiKeyEnv", p.apiKeyEnv());
            if (!p.requiresKey()) {
                m.put("requiresKey", false);
            }
            List<Object> models = new ArrayList<>();
            for (ModelConfig mc : p.models()) {
                if (mc.name() == null && mc.contextWindow() == null && mc.maxOutputTokens() == null) {
                    models.add(mc.id());
                } else {
                    Map<String, Object> one = new LinkedHashMap<>();
                    one.put("id", mc.id());
                    putIfNotNull(one, "name", mc.name());
                    putIfNotNull(one, "contextWindow", mc.contextWindow());
                    putIfNotNull(one, "maxOutputTokens", mc.maxOutputTokens());
                    models.add(one);
                }
            }
            m.put("models", models);
            out.put(id, m);
        });
        return out;
    }

    private static void putIfNotNull(Map<String, Object> m, String key, Object value) {
        if (value != null) {
            m.put(key, value);
        }
    }

    /** 文件格式 → 领域的 Settings。格式不对抛 IllegalArgumentException，{@link YamlFile} 会在消息前面加上文件路径 */
    static Settings convert(Map<String, Object> raw) {
        SettingsFile f = STRICT.convertValue(raw, SettingsFile.class);
        Map<String, ProviderConfig> providers = new LinkedHashMap<>();
        if (f.providers() != null) {
            f.providers().forEach((id, e) -> providers.put(id, toProvider(id, e)));
        }
        return new Settings(f.defaultModel(), providers);
    }

    private static ProviderConfig toProvider(String id, ProviderEntry e) {
        if (e == null) {
            throw new IllegalArgumentException("服务商 " + id + " 下面什么都没写");
        }
        List<ModelConfig> models = new ArrayList<>();
        if (e.models() != null) {
            for (Object o : e.models()) {
                models.add(toModel(id, o));
            }
        }
        return new ProviderConfig(id, e.name(), e.protocol(), e.baseUrl(), e.apiKeyEnv(),
            !Boolean.FALSE.equals(e.requiresKey()), models);
    }

    /** 模型可以只写名字（字符串），也可以写成带参数的形式（id、name、contextWindow、maxOutputTokens） */
    private static ModelConfig toModel(String providerId, Object o) {
        if (o instanceof String s) {
            return ModelConfig.of(s);
        }
        if (o instanceof Map<?, ?>) {
            ModelEntry m = STRICT.convertValue(o, ModelEntry.class);
            return new ModelConfig(m.id(), m.name(), m.contextWindow(), m.maxOutputTokens());
        }
        throw new IllegalArgumentException("服务商 " + providerId + " 的 models 里有一项看不懂：" + o);
    }
}
