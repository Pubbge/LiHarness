package com.pubbge.liharness.domain.llm.provider;

/**
 * 解析好的、可以直接用的一个模型：provider 和模型的配置合并、补齐默认值以后的结果。
 *
 * <p>{@link ProviderConfig} 是“用户写的配置”，字段可能缺；这个是“注册表算出来的结论”，字段一定齐全。
 * 适配器、上下文管理（第 12 章）、界面都只看它。
 *
 * @param ref             {@code provider/model}
 * @param displayName     界面上显示的名字
 * @param protocol        协议，决定用哪个适配器
 * @param baseUrl         服务地址
 * @param contextWindow   上下文窗口（token）
 * @param maxOutputTokens 每次调用的输出上限（token）
 */
public record ModelProfile(
        ModelRef ref,
        String displayName,
        String protocol,
        String baseUrl,
        int contextWindow,
        int maxOutputTokens) {

    /** 不知道模型参数时的默认上下文窗口。主流模型都至少有这么大 */
    public static final int DEFAULT_CONTEXT_WINDOW = 128_000;

    public ModelProfile {
        if (contextWindow < 4096) {
            throw new IllegalArgumentException(ref + "：上下文窗口至少 4096");
        }
        if (maxOutputTokens < 256 || maxOutputTokens >= contextWindow) {
            throw new IllegalArgumentException(ref + "：输出上限要在 256 到上下文窗口之间");
        }
    }

    /** 发给服务端的模型名 */
    public String model() {
        return ref.model();
    }
}
