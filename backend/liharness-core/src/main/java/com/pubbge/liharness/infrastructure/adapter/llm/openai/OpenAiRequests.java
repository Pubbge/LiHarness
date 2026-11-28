package com.pubbge.liharness.infrastructure.adapter.llm.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pubbge.liharness.domain.llm.exchange.LlmRequest;
import com.pubbge.liharness.domain.llm.message.Message;
import com.pubbge.liharness.domain.llm.tool.ToolCall;
import com.pubbge.liharness.domain.llm.tool.ToolSpec;
import com.pubbge.liharness.domain.shared.json.Json;

/**
 * 把我们的 {@link LlmRequest} 翻译成 OpenAI Chat Completions 的请求体。
 *
 * <p>这就是第 04 章说的“防腐层”在 OpenAI 这一侧的实现：各家格式的差异只在这里出现。
 */
final class OpenAiRequests {

    private OpenAiRequests() {
    }

    static String body(LlmRequest request, String model) {
        ObjectNode root = Json.mapper().createObjectNode();
        root.put("model", model);
        root.put("stream", true);
        // 让服务在流的最后一块里返回 token 用量。不加这个，流式响应里没有 usage
        root.putObject("stream_options").put("include_usage", true);
        // OpenAI 新模型改叫 max_completion_tokens，但兼容服务几乎都只认 max_tokens，所以用它
        root.put("max_tokens", request.maxOutputTokens());

        ArrayNode messages = root.putArray("messages");
        if (!request.system().isEmpty()) {
            // 第 04 章：我们的系统提示词是单独的字段；OpenAI 协议里它是第一条 system 消息
            messages.addObject().put("role", "system").put("content", request.system());
        }
        for (Message m : request.messages()) {
            switch (m.role()) {
                case USER -> messages.addObject().put("role", "user").put("content", m.text());
                case ASSISTANT -> {
                    ObjectNode node = messages.addObject().put("role", "assistant");
                    if (m.text().isEmpty()) {
                        node.putNull("content"); // 只有工具调用时，content 是 null 而不是空字符串
                    } else {
                        node.put("content", m.text());
                    }
                    if (m.hasToolCalls()) {
                        ArrayNode calls = node.putArray("tool_calls");
                        for (ToolCall c : m.toolCalls()) {
                            ObjectNode call = calls.addObject().put("id", c.id()).put("type", "function");
                            call.putObject("function").put("name", c.name()).put("arguments", c.argumentsJson());
                        }
                    }
                }
                case TOOL -> messages.addObject()
                    .put("role", "tool")
                    .put("tool_call_id", m.toolCallId())
                    // OpenAI 协议没有“工具失败”的标记（Anthropic 有 is_error），只能写进内容里让模型知道
                    .put("content", m.error() ? "[工具执行失败] " + m.text() : m.text());
            }
        }

        if (!request.tools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            for (ToolSpec t : request.tools()) {
                ObjectNode fn = tools.addObject().put("type", "function").putObject("function");
                fn.put("name", t.name()).put("description", t.description());
                try {
                    fn.set("parameters", Json.readTree(t.inputSchemaJson()));
                } catch (JsonProcessingException e) {
                    throw new IllegalArgumentException("工具 " + t.name() + " 的 Schema 不是合法 JSON", e);
                }
            }
        }
        return Json.write(root);
    }
}
