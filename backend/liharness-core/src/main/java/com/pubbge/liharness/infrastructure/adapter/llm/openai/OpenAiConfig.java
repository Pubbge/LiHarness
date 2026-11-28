package com.pubbge.liharness.infrastructure.adapter.llm.openai;

import java.net.URI;
import java.time.Duration;

/**
 * OpenAI 兼容服务的连接参数。
 *
 * @param baseUrl     接口地址，比如 https://api.deepseek.com。请求会发到 baseUrl + "/chat/completions"，
 *                    这一段由我们拼上；用户把完整地址（带 /chat/completions）贴进来也认
 * @param apiKey      API Key；本机的 Ollama 这类不需要 Key 的服务为 null
 * @param model       模型名
 * @param idleTimeout 流式响应中，两段数据之间最长等多久。超过就认为连接卡死了
 */
public record OpenAiConfig(String baseUrl, String apiKey, String model, Duration idleTimeout) {

    /**
     * 3 分钟。推理类模型在输出正文之前可能“思考”很久，期间有的服务一个字节都不发；
     * 太短会误判，太长则连接真卡死时用户要干等。
     */
    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(3);

    private static final String CHAT_COMPLETIONS = "/chat/completions";

    public OpenAiConfig {
        baseUrl = baseUrl.strip();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        if (baseUrl.endsWith(CHAT_COMPLETIONS)) { // 用户贴的是完整地址：去掉，下面统一拼
            baseUrl = baseUrl.substring(0, baseUrl.length() - CHAT_COMPLETIONS.length());
        }
    }

    public URI chatCompletionsUri() {
        return URI.create(baseUrl + CHAT_COMPLETIONS);
    }

    /** 不要让 API Key 出现在日志里：record 默认的 toString 会打印所有字段 */
    @Override
    public String toString() {
        return "OpenAiConfig[baseUrl=" + baseUrl + ", model=" + model + ", apiKey=***]";
    }
}
