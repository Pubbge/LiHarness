package com.pubbge.liharness.domain.shared.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/**
 * 领域层和基础设施层共用的 JSON 入口。
 *
 * <p>ObjectMapper 创建成本高、配置好之后线程安全，所以全局共用一个。所有 JSON 读写都走这里，
 * 以后要改配置（比如日期格式）只改一处。
 */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        // 模型和外部服务返回的 JSON 经常带我们不认识的字段，忽略它们而不是报错
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private Json() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** 解析一个 JSON 对象。不是对象（比如是数组或字符串）也算失败 */
    public static Map<String, Object> parseObject(String json) throws JsonProcessingException {
        JsonNode node = MAPPER.readTree(json);
        if (node == null || !node.isObject()) {
            throw new JsonProcessingException("不是 JSON 对象") { };
        }
        return MAPPER.convertValue(node, MAP);
    }

    public static JsonNode readTree(String json) throws JsonProcessingException {
        return MAPPER.readTree(json);
    }

    /** 序列化。我们自己的对象序列化失败是 bug，所以抛非受检异常 */
    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }
}
