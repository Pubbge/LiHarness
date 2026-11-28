package com.pubbge.liharness.app.config;

import com.pubbge.liharness.domain.session.adapter.repository.ISessionRepository;
import com.pubbge.liharness.infrastructure.adapter.repository.InMemorySessionRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 会话存储的装配。
 *
 * <p>domain 和 infrastructure 不认识 Spring（类上不能加 @Component），由这里 new 出来交给 Spring 管理。
 */
@Configuration
public class SessionConfig {

    @Bean
    public ISessionRepository sessionRepository() {
        return new InMemorySessionRepository();
    }
}
