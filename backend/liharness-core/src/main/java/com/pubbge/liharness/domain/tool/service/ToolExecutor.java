package com.pubbge.liharness.domain.tool.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.pubbge.liharness.domain.llm.tool.ToolCall;
import com.pubbge.liharness.domain.shared.cancel.CancelledException;
import com.pubbge.liharness.domain.shared.json.Json;
import com.pubbge.liharness.domain.spi.tool.Tool;
import com.pubbge.liharness.domain.spi.tool.ToolArgs;
import com.pubbge.liharness.domain.spi.tool.ToolException;
import com.pubbge.liharness.domain.spi.tool.ToolResult;
import com.pubbge.liharness.domain.tool.model.valobj.ToolCallScope;
import com.pubbge.liharness.domain.tool.model.valobj.ToolOutcome;
import com.pubbge.liharness.domain.tool.model.valobj.ToolSet;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * 执行工具调用的<b>唯一</b>入口。
 *
 * <p>不管工具来自哪里（内置、MCP、子代理、以后的插件），也不管是谁在编排（我们的 Agent Loop，
 * 还是以后接入的外部框架），工具调用都必须经过这里。参数校验、权限检查（第 10 章）、
 * 超长输出截断（第 07 章）、异常兜底都在这里做一次，所有工具自动获得。
 *
 * <p>约定：除了取消，任何失败都变成一个“失败的结果”返回，绝不抛异常，Agent Loop 不会因为工具崩溃。
 */
public final class ToolExecutor {

    private static final System.Logger log = System.getLogger(ToolExecutor.class.getName());

    /**
     * 执行一次工具调用。
     *
     * @throws CancelledException 用户已要求停止
     */
    public ToolOutcome execute(ToolCall call, ToolSet tools, ToolCallScope scope) {
        long start = System.nanoTime();
        ToolResult result = doExecute(call, tools, scope);
        // 工具执行期间被取消：不管工具返回了什么，都按取消处理，由 Agent Loop 统一收尾
        scope.cancel().throwIfCancelled();
        return new ToolOutcome(call, result, Duration.ofNanos(System.nanoTime() - start));
    }

    private ToolResult doExecute(ToolCall call, ToolSet tools, ToolCallScope scope) {
        scope.cancel().throwIfCancelled();

        Optional<Tool> found = tools.find(call.name());
        if (found.isEmpty()) {
            // 模型偶尔会编造工具名，或者调用一个这个回合没有提供的工具。告诉它有哪些可用
            return ToolResult.error("没有名为 " + call.name() + " 的工具。可用的工具：" + String.join(", ", tools.names()));
        }
        Tool tool = found.get();

        ToolArgs args;
        try {
            Map<String, Object> parsed = Json.parseObject(call.argumentsJson());
            args = new ToolArgs(parsed, call.argumentsJson());
        } catch (JsonProcessingException e) {
            return ToolResult.error("参数不是合法的 JSON 对象：" + abbreviate(call.argumentsJson(), 200));
        }

        try {
            ToolResult result = tool.call(args, new DefaultToolContext(scope, call.id()));
            return result == null ? ToolResult.error("工具没有返回结果") : result;
        } catch (ToolException e) {
            return ToolResult.error(e.getMessage());
        } catch (CancelledException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scope.cancel().cancel();
            throw new CancelledException();
        } catch (Exception e) {
            // 工具的 bug：记完整堆栈给开发者看，给模型一句简短的说明
            log.log(System.Logger.Level.WARNING, "工具 " + call.name() + " 执行异常", e);
            return ToolResult.error("工具内部错误：" + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    static String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
