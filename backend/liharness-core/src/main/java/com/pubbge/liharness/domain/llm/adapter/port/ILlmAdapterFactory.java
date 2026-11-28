package com.pubbge.liharness.domain.llm.adapter.port;

import com.pubbge.liharness.domain.llm.provider.ModelProfile;

/**
 * 某一种协议的适配器工厂：按模型配置创建 {@link LlmChatModelAdapter}。
 *
 * <p>每个协议一个实现，启动时<b>注册</b>到 {@code LlmProviderRegistry} 里，按 {@link #protocol()} 查找：
 *
 * <pre>
 * "mock"               → MockAdapterFactory
 * "openai-completions" → OpenAiAdapterFactory   （DeepSeek、OpenAI、Kimi、GLM、通义、Ollama…都走它）
 * 第二阶段            → "anthropic-messages" 等
 * </pre>
 *
 * <p>为什么按协议而不是按厂商注册：几十家模型服务，绝大多数说的是同一种协议（OpenAI Chat Completions），
 * 区别只是地址和 Key。按协议注册，加一家新服务只要在配置里写几行，不用写代码。
 */
public interface ILlmAdapterFactory {

    /** 协议名，和 settings.yaml 里的 {@code protocol} 对应 */
    String protocol();

    /**
     * 创建一个模型端口。只是组装对象，不联网，所以很便宜：每个回合都可以重新创建。
     *
     * @param apiKey API Key；不需要 Key 的协议传 null
     */
    LlmChatModelAdapter create(ModelProfile profile, String apiKey);
}
