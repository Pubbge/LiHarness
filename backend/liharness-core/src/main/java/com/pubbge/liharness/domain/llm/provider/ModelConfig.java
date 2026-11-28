package com.pubbge.liharness.domain.llm.provider;

/**
 * 一个 provider 下的一个模型的配置（settings.yaml 里 {@code models:} 下的一项）。
 *
 * <p>除了 id 都可以不写：不写就用默认值。上下文窗口第 12 章做上下文管理时用。
 *
 * @param id              模型 id，发给服务端的就是它
 * @param name            界面上显示的名字，可以为空
 * @param contextWindow   上下文窗口（token），可以为空
 * @param maxOutputTokens 每次调用的输出上限（token），可以为空
 */
public record ModelConfig(String id, String name, Integer contextWindow, Integer maxOutputTokens) {

    public ModelConfig {
        id = id == null ? "" : id.strip();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("模型的 id 不能为空");
        }
        name = name == null || name.isBlank() ? null : name.strip();
    }

    public static ModelConfig of(String id) {
        return new ModelConfig(id, null, null, null);
    }
}
