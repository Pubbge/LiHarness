package com.pubbge.liharness.domain.spi.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具参数：模型给的 JSON 对象，已经解析成 Map 并通过了 Schema 校验。
 *
 * <p>为什么不直接给工具一个 Jackson 的 {@code JsonNode}：plugin-api 不依赖任何第三方库，
 * 插件作者不会因为我们升级 Jackson 而被迫跟着升级。
 *
 * <p>取值方法在类型不对或缺少必填参数时抛 {@link ToolException}，错误会交给模型自己修正。
 */
public final class ToolArgs {

    private final Map<String, Object> values;
    private final String rawJson;

    public ToolArgs(Map<String, Object> values, String rawJson) {
        this.values = values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
        this.rawJson = rawJson == null ? "{}" : rawJson;
    }

    public static ToolArgs empty() {
        return new ToolArgs(Map.of(), "{}");
    }

    /** 模型给的原始 JSON，记日志、排查问题时用 */
    public String rawJson() {
        return rawJson;
    }

    public Map<String, Object> asMap() {
        return values;
    }

    public boolean has(String name) {
        return values.get(name) != null;
    }

    /** 必填字符串 */
    public String string(String name) throws ToolException {
        return optString(name).orElseThrow(() -> new ToolException("缺少参数 " + name));
    }

    public String string(String name, String defaultValue) throws ToolException {
        return optString(name).orElse(defaultValue);
    }

    public Optional<String> optString(String name) throws ToolException {
        Object v = values.get(name);
        if (v == null) {
            return Optional.empty();
        }
        if (!(v instanceof String s)) {
            throw new ToolException("参数 " + name + " 应该是字符串");
        }
        return Optional.of(s);
    }

    public int integer(String name, int defaultValue) throws ToolException {
        Object v = values.get(name);
        if (v == null) {
            return defaultValue;
        }
        // JSON 里的数字可能被解析成 Integer、Long、Double；模型偶尔还会把数字写成 "10"
        if (v instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) {
            return Math.toIntExact(n.longValue());
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                // 落到下面统一报错
            }
        }
        throw new ToolException("参数 " + name + " 应该是整数");
    }

    public boolean bool(String name, boolean defaultValue) throws ToolException {
        Object v = values.get(name);
        if (v == null) {
            return defaultValue;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        throw new ToolException("参数 " + name + " 应该是 true 或 false");
    }

    public List<String> stringList(String name) throws ToolException {
        Object v = values.get(name);
        if (v == null) {
            return List.of();
        }
        if (!(v instanceof List<?> list)) {
            throw new ToolException("参数 " + name + " 应该是字符串数组");
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String s)) {
                throw new ToolException("参数 " + name + " 的每一项都应该是字符串");
            }
            out.add(s);
        }
        return List.copyOf(out);
    }

    /** 对象数组，比如 todo_write 的 todos。每一项是一个 Map */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> objectList(String name) throws ToolException {
        Object v = values.get(name);
        if (v == null) {
            return List.of();
        }
        if (!(v instanceof List<?> list)) {
            throw new ToolException("参数 " + name + " 应该是数组");
        }
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                throw new ToolException("参数 " + name + " 的每一项都应该是对象");
            }
            out.add((Map<String, Object>) m);
        }
        return List.copyOf(out);
    }

    @Override
    public String toString() {
        return rawJson;
    }
}
