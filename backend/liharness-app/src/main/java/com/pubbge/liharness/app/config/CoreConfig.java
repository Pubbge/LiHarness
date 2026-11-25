package com.pubbge.liharness.app.config;

import com.pubbge.liharness.core.llm.ChatModel;
import com.pubbge.liharness.core.llm.mock.MockChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把 liharness-core 里的对象装配成 Spring Bean。
 *
 */
@Configuration
public class CoreConfig {

    @Bean
    public ChatModel chatModel() {
        return new MockChatModel();
    }
}
