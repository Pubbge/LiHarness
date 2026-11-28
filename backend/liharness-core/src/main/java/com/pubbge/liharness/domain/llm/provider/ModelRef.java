package com.pubbge.liharness.domain.llm.provider;

/**
 * 指向一个模型：哪个 provider 的哪个模型，写法是 {@code provider/model}，例如 {@code deepseek/deepseek-v4-flash}。
 *
 * <p>为什么要带 provider：同一个模型名可能在好几个地方都能用（DeepSeek 官方、阿里云百炼、本地 Ollama），
 * 地址、Key、计费都不一样。只写模型名分不清是哪一个。
 *
 * <p>模型名里可以再有斜杠（有的网关用 {@code anthropic/claude-sonnet} 这种名字），所以只按<b>第一个</b>斜杠切分。
 *
 * @param provider provider 的 id，比如 {@code deepseek}
 * @param model    模型 id，发给服务端的就是它
 */
public record ModelRef(String provider, String model) {

    public ModelRef {
        provider = provider == null ? "" : provider.strip();
        model = model == null ? "" : model.strip();
        if (provider.isEmpty() || model.isEmpty()) {
            throw new IllegalArgumentException("模型要写成 provider/model，比如 deepseek/deepseek-v4-flash");
        }
    }

    /** 解析 {@code provider/model} */
    public static ModelRef parse(String text) {
        String t = text == null ? "" : text.strip();
        int slash = t.indexOf('/');
        if (slash <= 0 || slash == t.length() - 1) {
            throw new IllegalArgumentException("模型要写成 provider/model，比如 deepseek/deepseek-v4-flash：" + text);
        }
        return new ModelRef(t.substring(0, slash), t.substring(slash + 1));
    }

    @Override
    public String toString() {
        return provider + "/" + model;
    }
}
