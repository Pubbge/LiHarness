package com.pubbge.liharness.domain.agent.model.valobj;

import com.pubbge.liharness.domain.llm.adapter.port.LlmChatModelAdapter;
import com.pubbge.liharness.domain.llm.exchange.LlmRequest;
import com.pubbge.liharness.domain.llm.message.Message;
import com.pubbge.liharness.domain.tool.model.valobj.ToolSet;
import java.nio.file.Path;
import java.util.List;

/**
 * 一个回合的全部输入。
 *
 * <p>注意 model、tools、systemPrompt 都是<b>每个回合传进来</b>的，而不是 AgentLoop 的成员变量。
 * 所以同一个 AgentLoop 可以同时服务：用不同模型的会话（第 15 章）、只有部分工具的子代理（第 13 章）、
 * 只读的计划模式（第 10 章）。
 *
 * @param sessionId       会话 id
 * @param turnId          回合 id
 * @param model           这个回合用的模型
 * @param systemPrompt    系统提示词（第 12 章起由 SystemPromptBuilder 组装）
 * @param history         之前的对话历史（不含本回合的用户输入）
 * @param userInput       本回合的用户输入
 * @param tools           本回合可用的工具
 * @param workspace       工作区根目录
 * @param maxOutputTokens 每次调用模型的输出上限
 */
public record TurnRequest(
        String sessionId,
        String turnId,
        LlmChatModelAdapter model,
        String systemPrompt,
        List<Message> history,
        String userInput,
        ToolSet tools,
        Path workspace,
        int maxOutputTokens) {

    public TurnRequest {
        if (model == null) {
            throw new IllegalArgumentException("model 不能为空");
        }
        if (userInput == null || userInput.isBlank()) {
            throw new IllegalArgumentException("userInput 不能为空");
        }
        history = history == null ? List.of() : List.copyOf(history);
        tools = tools == null ? ToolSet.empty() : tools;
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        maxOutputTokens = maxOutputTokens <= 0 ? LlmRequest.DEFAULT_MAX_OUTPUT_TOKENS : maxOutputTokens;
    }
}
