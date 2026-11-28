# ADR-0009：外部框架（Spring AI、Google ADK）通过适配器接入，不进入内核

- 状态：已采纳
- 日期：2026-11-26

## 背景

业务复杂以后，可能要用到 Spring AI（模型接入、向量检索）和 Google ADK（多代理编排）。问题是：要不要从一开始就让内核建立在它们之上？

参考：同类的 Java Harness 项目（比如 deepseek-harness-java）也没有使用这两个框架，而是手写了 JDK HttpClient 适配器和 ReAct 循环，再用 ArchUnit 守住分层。

## 决定

**内核不依赖任何 AI 框架。** 外部框架只能从以下三个位置接入，每个位置都已经有现成的接口：

| 接入位置 | 接口 | 例子 | 阶段 |
|---|---|---|---|
| 模型适配器 | `LlmChatModelAdapter`（`stream(LlmRequest)` → `LlmStream`） | 写一个 `SpringAiLlmAdapter`，内部调用 Spring AI 的 `ChatClient`，把它的流翻译成我们的 `StreamChunk` | 二（第 15 章的多模型） |
| 工具 / 子代理 | `Tool`（`liharness-plugin-api`） | 写一个 `AdkAgentTool`：输入一段任务描述，内部运行一个 ADK 多代理流程，返回结果。对主代理来说它和 `task` 子代理一样 | 三 |
| 外层编排 | 我们的 HTTP 接口（`/api/agent/...`） | ADK 作为外层编排者，把 LiHarness 当作一个“能操作本机的代理”来调用 | 三 |

内核里的 Agent 循环、会话、上下文、权限，始终是我们自己的实现。

## 备选方案

| 方案 | 不选的原因 |
|---|---|
| 内核直接用 Spring AI 的 `ChatClient` 和工具调用 | 内核会依赖 Spring（违反 ADR-0003/0007 的“core 不依赖 Spring”）；Spring AI 的工具调用循环是黑盒，审批、取消、上下文压缩这些 Harness 的核心能力很难插进去 |
| 内核用 ADK 的 Agent 作为循环 | 同上，循环不在我们手里，学不到 Harness 的核心；ADK 的 Java 版本和生态还在快速变化 |
| 完全不考虑外部框架 | 以后真要接入时没有位置可放 |

## 后果

好的：

- 学习路线不变：第一阶段完整手写 Harness 的每一部分
- 接入外部框架不需要改内核，只需要实现一个已有接口
- 外部框架升级或被替换，影响范围只有那一个适配器

坏的：

- 外部框架的一些便利（比如 Spring AI 自动把方法变成工具）要自己写一层转换
- 外部编排出来的子代理内部过程，我们的界面看不到细节，只能看到最终结果（和 `task` 子代理一样）
