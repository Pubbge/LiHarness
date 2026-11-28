package com.pubbge.liharness.domain.tool.model.valobj;

import com.pubbge.liharness.domain.llm.tool.ToolSpec;
import com.pubbge.liharness.domain.spi.tool.Tool;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 一个回合可以使用的工具集合。不可变。
 *
 * <p>为什么是“每个回合一份”而不是全局一份：子代理（第 13 章）只能用部分工具，
 * 计划模式（第 10 章）只给只读工具，MCP 服务器（第 14 章）随时可能上线下线。
 * 每个回合开始时组装一份快照，回合进行中不会变。
 */
public final class ToolSet {

    private static final ToolSet EMPTY = new ToolSet(Map.of());

    /** 保持插入顺序：工具在发给模型的清单里的顺序是稳定的，便于对比和缓存 */
    private final Map<String, Tool> tools;

    private ToolSet(Map<String, Tool> tools) {
        this.tools = Collections.unmodifiableMap(tools);
    }

    public static ToolSet empty() {
        return EMPTY;
    }

    public static ToolSet of(Tool... tools) {
        return of(Arrays.asList(tools));
    }

    public static ToolSet of(Collection<? extends Tool> tools) {
        Map<String, Tool> map = new LinkedHashMap<>();
        for (Tool tool : tools) {
            // 构造一次 ToolSpec：借用它的名字校验，非法工具名在组装时就报错，而不是等到发给模型时
            new ToolSpec(tool.name(), tool.description(), tool.inputSchema());
            if (map.putIfAbsent(tool.name(), tool) != null) {
                throw new IllegalArgumentException("工具名重复：" + tool.name());
            }
        }
        return new ToolSet(map);
    }

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public Set<String> names() {
        return tools.keySet();
    }

    public Collection<Tool> all() {
        return tools.values();
    }

    public boolean isEmpty() {
        return tools.isEmpty();
    }

    /** 发给模型的工具清单 */
    public List<ToolSpec> specs() {
        List<ToolSpec> specs = new ArrayList<>(tools.size());
        for (Tool t : tools.values()) {
            specs.add(new ToolSpec(t.name(), t.description(), t.inputSchema()));
        }
        return specs;
    }

    /** 加上一些工具，返回新集合 */
    public ToolSet with(Collection<? extends Tool> more) {
        List<Tool> all = new ArrayList<>(tools.values());
        all.addAll(more);
        return of(all);
    }

    /** 只保留满足条件的工具，返回新集合 */
    public ToolSet filter(Predicate<Tool> keep) {
        return of(tools.values().stream().filter(keep).toList());
    }

    /** 去掉指定名字的工具，返回新集合 */
    public ToolSet without(String... names) {
        Set<String> drop = Set.of(names);
        return filter(t -> !drop.contains(t.name()));
    }
}
