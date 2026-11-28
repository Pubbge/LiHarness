package com.pubbge.liharness.app.config;

import com.pubbge.liharness.domain.tool.model.valobj.ToolSet;
import com.pubbge.liharness.domain.tool.service.ToolExecutor;
import com.pubbge.liharness.domain.tool.time.GetTimeTool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 工具相关的装配：内置工具、工具执行器。
 *
 * <p>domain 和 infrastructure 不认识 Spring（类上不能加 @Component），由这里 new 出来交给 Spring 管理。
 */
@Configuration
public class ToolConfig {

    @Bean
    public ToolExecutor toolExecutor() {
        return new ToolExecutor();
    }

    @Bean
    public ToolSet builtinTools() {
        return ToolSet.of(new GetTimeTool());
    }
}
