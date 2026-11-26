package com.pubbge.liharness.app.config;

import com.pubbge.liharness.domain.llm.adapter.port.ILlmPort;
import com.pubbge.liharness.infrastructure.adapter.llm.mock.MockLlmAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 模型相关的装配。
 *
 * <p>为什么在 app 层 new：domain 和 infrastructure 不认识 Spring（类上不能加 @Component），
 * 由这里把具体的适配器（MockLlmAdapter）作为端口（ILlmPort）交给 Spring 管理。
 * 业务代码只依赖端口，换成真实模型时只改这一处。
 *
 * <p>第 07 章起，每个回合用哪个模型由 cases.settings.ModelCase 按设置决定，这个 Bean 就不需要了。
 */
@Configuration
public class LlmConfig {

    @Bean
    public ILlmPort llm() {
        return new MockLlmAdapter();
    }
}
