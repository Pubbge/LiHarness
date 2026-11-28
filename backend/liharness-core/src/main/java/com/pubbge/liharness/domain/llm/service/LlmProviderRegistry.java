package com.pubbge.liharness.domain.llm.service;

import com.pubbge.liharness.domain.llm.adapter.port.ILlmAdapterFactory;
import com.pubbge.liharness.domain.llm.adapter.port.LlmChatModelAdapter;
import com.pubbge.liharness.domain.llm.error.LlmException;
import com.pubbge.liharness.domain.llm.error.LlmException.Kind;
import com.pubbge.liharness.domain.llm.provider.ModelConfig;
import com.pubbge.liharness.domain.llm.provider.ModelProfile;
import com.pubbge.liharness.domain.llm.provider.ModelRef;
import com.pubbge.liharness.domain.llm.provider.ProviderConfig;
import com.pubbge.liharness.domain.settings.adapter.repository.ICredentialRepository;
import com.pubbge.liharness.domain.settings.adapter.repository.ISettingsRepository;
import com.pubbge.liharness.domain.settings.model.valobj.Settings;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * 模型注册表：回答“{@code 服务商/模型} 指的是什么、怎么和它说话”。
 *
 * <p>它管两张表：
 * <ol>
 *   <li><b>服务商表</b>：就是 settings.yaml 的 {@code providers:}（你在设置页里添加的那些），外加一个永远可用、
 *       不联网的 {@code mock}。每次调用都现读文件，所以改了配置不用重启。</li>
 *   <li><b>协议表</b>：接口格式 → 会说这种格式的适配器工厂。启动时登记一次，现在只有两种：
 *       {@code openai-completions}（DeepSeek、通义、Kimi……都是）和 {@code mock}。</li>
 * </ol>
 *
 * <pre>
 * defaultModel  "deepseek/deepseek-v4-flash"
 *   │  ① 切开：服务商 deepseek，模型 deepseek-v4-flash
 *   ▼
 * 服务商表 ──② 找到 deepseek，检查模型在它的列表里、地址合法 ──► ModelProfile
 *                                                                   │
 * .credentials.yaml / 环境变量 ──③ 找 Key ─────────────────────────┤
 * 协议表 ──④ 按协议取工厂 ────────────────────────────────────────► LlmChatModelAdapter
 * </pre>
 */
public final class LlmProviderRegistry {

    /** 永远可用的测试模型，一个服务商都没配时用它 */
    public static final ModelRef MOCK = new ModelRef("mock", "mock");
    static final ProviderConfig MOCK_PROVIDER = new ProviderConfig(
        "mock", "Mock（不联网）", "mock", null, null, false, List.of(new ModelConfig("mock", "Mock", null, null)));
    /** 不知道模型参数时的默认输出上限 */
    static final int DEFAULT_MAX_OUTPUT_TOKENS = 8192;

    /** Key 从哪来，给设置页显示状态用（不含 Key 本身） */
    public enum KeySource { SAVED, ENV, NOT_REQUIRED, MISSING }

    private final ISettingsRepository settings;
    private final ICredentialRepository credentials;
    private final Function<String, String> env;
    private final Map<String, ILlmAdapterFactory> adapters = new LinkedHashMap<>();

    /**
     * @param env       读环境变量，正式运行时传 {@code System::getenv}，测试里传一个 Map
     * @param adapters  支持的协议
     */
    public LlmProviderRegistry(ISettingsRepository settings, ICredentialRepository credentials,
            Function<String, String> env, List<ILlmAdapterFactory> adapters) {
        this.settings = settings;
        this.credentials = credentials;
        this.env = env;
        for (ILlmAdapterFactory a : adapters) {
            if (this.adapters.putIfAbsent(a.protocol(), a) != null) {
                throw new IllegalArgumentException("协议重复注册：" + a.protocol());
            }
        }
    }

    /** 你添加的服务商，顺序和 settings.yaml 里一样（不含 mock） */
    public List<ProviderConfig> providers() throws LlmException {
        return new ArrayList<>(load().providers().values());
    }

    /**
     * 当前用的模型：settings.yaml 的 defaultModel；没写就用第一个服务商的第一个模型；一个服务商都没有就用 Mock。
     */
    public ModelRef defaultModel() throws LlmException {
        Settings s = load();
        if (s.defaultModel() != null) {
            try {
                return ModelRef.parse(s.defaultModel());
            } catch (IllegalArgumentException e) {
                throw notConfigured("settings.yaml 里的 defaultModel 写错了：" + e.getMessage());
            }
        }
        return s.providers().values().stream()
            .filter(p -> !p.models().isEmpty())
            .findFirst()
            .map(p -> new ModelRef(p.id(), p.models().get(0).id()))
            .orElse(MOCK);
    }

    /**
     * 把 {@code 服务商/模型} 解析成完整的模型配置。
     *
     * @throws LlmException {@link Kind#NOT_CONFIGURED}：没有这个服务商、模型不在它的列表里、协议不支持、地址不对
     */
    public ModelProfile resolve(ModelRef ref) throws LlmException {
        ProviderConfig p = provider(ref.provider());
        if (!adapters.containsKey(p.protocol())) {
            throw notConfigured("服务商 " + p.name() + " 的接口格式 " + p.protocol() + " 不支持，支持的：" + adapters.keySet());
        }
        if (!"mock".equals(p.protocol())) {
            checkUrl(p);
        }
        ModelConfig m = p.model(ref.model()).orElseThrow(() -> notConfigured(p.models().isEmpty()
            ? "服务商 " + p.name() + " 还没有填写模型，去设置页点“编辑”加上"
            : "服务商 " + p.name() + " 下没有模型 " + ref.model() + "，可选的：" + p.modelIds()));
        int window = m.contextWindow() != null ? m.contextWindow() : ModelProfile.DEFAULT_CONTEXT_WINDOW;
        int maxOut = m.maxOutputTokens() != null ? m.maxOutputTokens() : Math.min(DEFAULT_MAX_OUTPUT_TOKENS, window / 2);
        String display = p.name() + " · " + (m.name() == null ? m.id() : m.name());
        try {
            return new ModelProfile(ref, display, p.protocol(), p.baseUrl(), window, maxOut);
        } catch (IllegalArgumentException e) {
            throw notConfigured(e.getMessage());
        }
    }

    /**
     * 创建和这个模型说话的适配器。
     *
     * @throws LlmException {@link Kind#NOT_CONFIGURED}：需要 Key 但哪里都没找到
     */
    public LlmChatModelAdapter open(ModelProfile profile) throws LlmException {
        ProviderConfig p = provider(profile.ref().provider());
        String key = apiKey(p).orElse(null);
        if (key == null && p.requiresKey()) {
            throw notConfigured("服务商 " + p.name() + " 还没有 API Key。在设置页的“模型”里点它的“编辑”填上，"
                + "或者在 .credentials.yaml 里加一行 " + p.id() + ": <你的 Key>");
        }
        return adapters.get(profile.protocol()).create(profile, key);
    }

    /** 当前模型的快捷方式：resolve + open */
    public LlmChatModelAdapter openDefault() throws LlmException {
        return open(resolve(defaultModel()));
    }

    /** 找 Key：先找 .credentials.yaml 里以服务商 id 为名的那一行，没有再读 apiKeyEnv 指定的环境变量 */
    public Optional<String> apiKey(ProviderConfig p) {
        Optional<String> saved = credentials.find(p.id()).filter(s -> !s.isBlank());
        if (saved.isPresent()) {
            return saved;
        }
        if (p.apiKeyEnv() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(env.apply(p.apiKeyEnv())).filter(s -> !s.isBlank());
    }

    /** Key 的来源，给设置页显示 */
    public KeySource keySource(ProviderConfig p) {
        if (credentials.find(p.id()).filter(s -> !s.isBlank()).isPresent()) {
            return KeySource.SAVED;
        }
        if (apiKey(p).isPresent()) {
            return KeySource.ENV;
        }
        return p.requiresKey() ? KeySource.MISSING : KeySource.NOT_REQUIRED;
    }

    /** 按 id 找服务商；{@code mock} 永远存在 */
    public ProviderConfig provider(String id) throws LlmException {
        if (MOCK_PROVIDER.id().equals(id)) {
            return MOCK_PROVIDER;
        }
        ProviderConfig p = load().providers().get(id);
        if (p == null) {
            throw notConfigured("没有叫 " + id + " 的服务商，先在设置页的“模型”里添加它");
        }
        return p;
    }

    private static void checkUrl(ProviderConfig p) throws LlmException {
        String url = p.baseUrl();
        URI uri;
        try {
            uri = URI.create(url == null ? "" : url);
        } catch (IllegalArgumentException e) {
            throw notConfigured("服务商 " + p.name() + " 的接口地址不合法：" + url);
        }
        if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme()) || uri.getHost() == null) {
            throw notConfigured("服务商 " + p.name() + " 的接口地址要以 http:// 或 https:// 开头：" + url);
        }
    }

    private Settings load() throws LlmException {
        try {
            return settings.current();
        } catch (IllegalStateException e) {
            throw notConfigured(e.getMessage());
        }
    }

    private static LlmException notConfigured(String message) {
        return new LlmException(Kind.NOT_CONFIGURED, message);
    }
}
