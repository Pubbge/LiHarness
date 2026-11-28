package com.pubbge.liharness.infrastructure.adapter.llm.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 接口地址只写到 /chat/completions 前面；贴了完整地址、多了斜杠也能用 */
class OpenAiConfigTest {

    private static String uri(String baseUrl) {
        return new OpenAiConfig(baseUrl, null, "m", OpenAiConfig.DEFAULT_IDLE_TIMEOUT).chatCompletionsUri().toString();
    }

    @Test
    void chatCompletionsIsAppendedByUs() {
        assertEquals("https://api.deepseek.com/chat/completions", uri("https://api.deepseek.com"));
        assertEquals("https://api.deepseek.com/chat/completions", uri("https://api.deepseek.com/"));
        assertEquals("https://x.example/v1/chat/completions", uri("https://x.example/v1/chat/completions"));
        assertEquals("https://x.example/v1/chat/completions", uri(" https://x.example/v1/ "));
    }
}
