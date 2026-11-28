package com.pubbge.liharness.domain.llm.tool;

import com.pubbge.liharness.domain.shared.validate.Texts;

/**
 * 模型发起的一次工具调用。
 *
 * @param id            调用 id，由模型服务生成。工具结果用它“对号入座”
 * @param name          工具名
 * @param argumentsJson 参数 JSON 原文。这是模型“写”出来的，不保证合法，执行前必须校验（第 07 章）
 */
public record ToolCall(String id, String name, String argumentsJson) {

    public ToolCall {
        id = Texts.requireNonBlank(id, "id");
        name = Texts.requireNonBlank(name, "name");
        // 无参工具，有的模型服务给 ""，有的给 null，有的给 "{}"。在边界上统一成 "{}"
        argumentsJson = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
    }

    @Override
    public String toString() {
        return name + "#" + id + argumentsJson;
    }
}
