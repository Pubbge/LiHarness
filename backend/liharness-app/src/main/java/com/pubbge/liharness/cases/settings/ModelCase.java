package com.pubbge.liharness.cases.settings;

import com.pubbge.liharness.app.config.HarnessHome;
import com.pubbge.liharness.domain.llm.adapter.port.LlmChatModelAdapter;
import com.pubbge.liharness.domain.llm.error.LlmException;
import com.pubbge.liharness.domain.llm.provider.ModelConfig;
import com.pubbge.liharness.domain.llm.provider.ModelProfile;
import com.pubbge.liharness.domain.llm.provider.ModelRef;
import com.pubbge.liharness.domain.llm.provider.ProviderConfig;
import com.pubbge.liharness.domain.llm.service.LlmProviderRegistry;
import com.pubbge.liharness.domain.llm.service.LlmProviderRegistry.KeySource;
import com.pubbge.liharness.domain.settings.adapter.repository.ICredentialRepository;
import com.pubbge.liharness.domain.settings.adapter.repository.ISettingsRepository;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 模型的用例：这一次用哪个模型；设置页上列出、添加、修改、删除服务商；切换当前模型。
 *
 * <p>设置页做的事，都是改 {@code ~/.liharness} 下的两个文件：服务商和当前模型写进 settings.yaml，
 * Key 写进 .credentials.yaml。用编辑器改这两个文件效果完全一样。
 */
@Service
public class ModelCase {

    /** 当前模型：解析好的配置 + 由它创建的适配器 */
    public record ActiveModel(ModelProfile profile, LlmChatModelAdapter model) {
    }

    /** 设置页上的一个服务商。没有 Key 本身，只有 Key 的状态 */
    public record ProviderView(String id, String name, String baseUrl, List<String> models, KeySource keySource,
            boolean requiresKey) {
    }

    /**
     * 设置页需要的全部信息。
     *
     * @param currentModel 当前模型 {@code 服务商/模型}
     * @param currentLabel 当前模型在界面上显示的名字
     * @param problem      配置有问题时的说明（比如 settings.yaml 写错了），没问题是 null
     */
    public record Overview(String currentModel, String currentLabel, String problem, List<ProviderView> providers,
            String settingsFile, String credentialsFile) {
    }

    /** 设置页“添加 / 编辑服务商”表单提交的内容。apiKey 为空表示不修改 */
    public record ProviderForm(String name, String baseUrl, List<String> models, String apiKey, Boolean requiresKey) {
    }

    private final LlmProviderRegistry registry;
    private final ISettingsRepository settings;
    private final ICredentialRepository credentials;
    private final HarnessHome home;

    public ModelCase(LlmProviderRegistry registry, ISettingsRepository settings, ICredentialRepository credentials,
            HarnessHome home) {
        this.registry = registry;
        this.settings = settings;
        this.credentials = credentials;
        this.home = home;
    }

    /** 每个回合开始时调用：取当前模型。没配好时抛 NOT_CONFIGURED，消息里说明该怎么改 */
    public ActiveModel current() throws LlmException {
        ModelProfile profile = registry.resolve(registry.defaultModel());
        return new ActiveModel(profile, registry.open(profile));
    }

    /** 设置页和模型选择器要显示的内容。配置文件写错了也不抛异常：把问题放进 problem，界面照常打开 */
    public Overview overview() {
        List<ProviderView> providers = new ArrayList<>();
        String current = null;
        String label = null;
        String problem = null;
        try {
            for (ProviderConfig p : registry.providers()) {
                providers.add(new ProviderView(p.id(), p.name(), p.baseUrl(), p.modelIds(), registry.keySource(p),
                    p.requiresKey()));
            }
            ModelRef ref = registry.defaultModel();
            current = ref.toString();
            label = registry.resolve(ref).displayName();
        } catch (LlmException e) {
            problem = e.getMessage();
        }
        return new Overview(current, label, problem, providers,
            home.settingsFile().toString(), home.credentialsFile().toString());
    }

    /** 切换当前模型（写进 settings.yaml 的 defaultModel），下一次提问生效 */
    public Overview selectModel(String model) throws LlmException {
        ModelRef ref;
        try {
            ref = ModelRef.parse(model);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("模型要写成 服务商/模型，比如 deepseek/deepseek-v4-flash");
        }
        registry.resolve(ref); // 服务商和模型都存在才保存；Key 没填不拦着，提问时会提示
        settings.update(s -> s.withDefaultModel(ref.toString()));
        return overview();
    }

    /**
     * 添加或修改一个服务商。
     *
     * <p>修改时保留表单里没有的字段（接口格式、环境变量名）和模型的参数（上下文窗口等，只要模型名没变）。
     * 还没有选过模型时，把这个服务商的第一个模型设为当前模型。
     */
    public Overview saveProvider(String id, ProviderForm form) {
        if ("mock".equals(id)) {
            throw new IllegalArgumentException("mock 是内置的测试模型，换一个标识");
        }
        List<String> modelIds = new ArrayList<>(new LinkedHashSet<>(
            (form.models() == null ? List.<String>of() : form.models()).stream().map(String::strip).filter(m -> !m.isEmpty()).toList()));
        if (modelIds.isEmpty()) {
            throw new IllegalArgumentException("至少填一个模型名");
        }
        checkUrl(form.baseUrl());
        ProviderConfig old = settings.current().providers().get(id);
        List<ModelConfig> models = modelIds.stream()
            .map(m -> old == null ? ModelConfig.of(m) : old.model(m).orElse(ModelConfig.of(m)))
            .toList();
        boolean requiresKey = form.requiresKey() != null ? form.requiresKey() : old == null || old.requiresKey();
        ProviderConfig p = new ProviderConfig(id, form.name(), old == null ? null : old.protocol(), form.baseUrl(),
            old == null ? null : old.apiKeyEnv(), requiresKey, models);
        if (old == null && requiresKey && (form.apiKey() == null || form.apiKey().isBlank())
                && credentials.find(id).isEmpty()) {
            throw new IllegalArgumentException("请填写 " + p.name() + " 的 API Key");
        }
        settings.update(s -> {
            var next = s.withProvider(p);
            return s.defaultModel() == null ? next.withDefaultModel(id + "/" + modelIds.get(0)) : next;
        });
        if (form.apiKey() != null && !form.apiKey().isBlank()) {
            credentials.save(id, form.apiKey());
        }
        return overview();
    }

    /** 删除服务商，连同保存的 Key。当前模型属于它的话，换成剩下的第一个服务商的第一个模型 */
    public Overview deleteProvider(String id) {
        settings.update(s -> {
            var next = s.withoutProvider(id);
            if (s.defaultModel() != null && s.defaultModel().startsWith(id + "/")) {
                next = next.withDefaultModel(next.providers().values().stream()
                    .filter(p -> !p.models().isEmpty())
                    .findFirst().map(p -> p.id() + "/" + p.models().get(0).id()).orElse(null));
            }
            return next;
        });
        credentials.remove(id);
        return overview();
    }

    private static void checkUrl(String url) {
        try {
            URI u = URI.create(url == null ? "" : url.strip());
            if (("http".equals(u.getScheme()) || "https".equals(u.getScheme())) && u.getHost() != null) {
                return;
            }
        } catch (IllegalArgumentException e) {
            // 落到下面统一报错
        }
        throw new IllegalArgumentException("接口地址要以 http:// 或 https:// 开头，比如 https://api.deepseek.com");
    }
}
