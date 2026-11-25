# ADR-0003：与框架无关的插件微内核，Spring Boot 只做应用层

- 状态：已废弃（被 [ADR-0007](0007-fixed-core-extension-points.md) 取代）
- 日期：2026-11-24

## 背景

产品目标是“万物皆插件”：模型、工具、会话存储、护栏、审批、甚至 Agent 循环都可替换，并且支持第三方插件独立开发与分发。

## 决定

1. `liharness-plugin-api`：**唯一的插件契约**，纯 Java 17，零第三方依赖（日志用 JDK 自带的 `System.Logger`）；
2. `liharness-kernel`：纯 Java 微内核，只负责插件加载、依赖解析、生命周期、服务注册与事件总线；
3. Spring Boot 只出现在 `liharness-app`：HTTP、SSE、配置、H2、健康检查，把内核当作一个普通 Bean 组装；
4. 插件**只能**依赖 `plugin-api`，由 Maven 模块依赖和（外部插件的）独立 ClassLoader 共同保证；
5. 内核通过 `inject()` 显式声明依赖 + `ctx.get()` 取服务，不使用注解扫描。

## 备选方案

| 方案 | 不选的原因 |
|---|---|
| 插件 = Spring Bean / Spring Boot Starter | 插件被绑死在 Spring 及其版本上；热插拔困难（ApplicationContext 不适合频繁增删） |
| PF4J 等现成插件框架 | 可行，但会隐藏我们想学习和掌控的生命周期细节；内核本身只有几百行 |
| OSGi | 能力强但复杂度过高 |

## 后果

好的：
- 插件可以独立编译、测试、分发，不受 Spring 升级影响
- 内核可在单元测试中直接运行，不需要启动 Spring
- 将来可以把内核嵌入 CLI、IDE 插件等其他宿主

坏的：
- 需要自己实现依赖编排与撤销机制（第 04 章）
- Spring 的部分便利（如自动配置）不能直接用于插件，需要通过内核服务桥接
