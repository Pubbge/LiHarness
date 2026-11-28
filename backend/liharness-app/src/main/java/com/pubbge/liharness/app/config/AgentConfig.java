package com.pubbge.liharness.app.config;

import com.pubbge.liharness.domain.agent.model.valobj.AgentOptions;
import com.pubbge.liharness.domain.agent.service.run.AgentLoop;
import com.pubbge.liharness.domain.tool.service.ToolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent Loop 的装配。
 *
 * <p>domain 和 infrastructure 不认识 Spring（类上不能加 @Component），由这里 new 出来交给 Spring 管理。
 */
@Configuration
public class AgentConfig {

    @Bean
    public AgentLoop agentLoop(ToolExecutor toolExecutor) {
        return new AgentLoop(toolExecutor, AgentOptions.DEFAULT);
    }
}
