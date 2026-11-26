package com.pubbge.liharness.domain.llm.model.valobj;

import java.util.regex.Pattern;

/**
 * 发给模型的一份工具说明：模型只能通过它了解一个工具。
 *
 * @param name            工具名，只能由字母、数字、下划线、短横线组成，1–64 个字符
 * @param description     写给模型看的：做什么、什么时候用、返回什么
 * @param inputSchemaJson 参数的 JSON Schema（字符串形式，第 06 章由工具自己声明）
 */
public record ToolSpec(String name, String description, String inputSchemaJson) {

    /** Anthropic 和 OpenAI 对工具名的限制取交集，这样同一个工具发给哪家都合法 */
    private static final Pattern NAME = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    /** 无参工具的 Schema */
    public static final String NO_ARGS_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";

    public ToolSpec {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("工具名不合法：" + name + "（只能用字母、数字、_、-，最长 64）");
        }
        description = Texts.requireNonBlank(description, "description");
        inputSchemaJson = inputSchemaJson == null || inputSchemaJson.isBlank() ? NO_ARGS_SCHEMA : inputSchemaJson;
    }
}
