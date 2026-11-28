package com.pubbge.liharness.domain.spi.tool;

/**
 * 工具：模型能调用的一个能力。
 *
 * <p>这是 LiHarness 对外最重要的接口：内置工具、MCP 工具（第 14 章）、以后的插件工具（第 16 章）
 * 都实现它。所以它放在 {@code liharness-plugin-api} 模块里，只依赖 JDK。
 *
 * <p>实现要求：
 * <ul>
 *   <li><b>无状态或线程安全</b>：同一个实例会被多个回合同时调用。</li>
 *   <li><b>只通过返回值和 {@link ToolContext} 与外界交互</b>：不要自己打印日志给用户看、不要自己弹窗。</li>
 *   <li><b>可取消</b>：耗时操作要检查 {@link ToolContext#isCancelled()}，或用 {@link ToolContext#onCancel} 注册中止动作。</li>
 * </ul>
 */
public interface Tool {

    /** 工具名：字母、数字、下划线、短横线，1–64 个字符。模型用它来调用工具 */
    String name();

    /**
     * 写给模型看的说明：做什么、什么时候用、什么时候不该用、返回什么。
     * 模型完全靠这段文字决定要不要调用这个工具，所以它和代码一样重要。
     */
    String description();

    /** 参数的 JSON Schema，顶层必须是 {@code "type": "object"} */
    String inputSchema();

    /**
     * 只读工具：只查看、不修改任何东西（文件、进程、网络上的状态）。
     * 第 10 章的权限策略据此决定是否需要用户批准；只读工具默认不打扰用户。
     */
    default boolean readOnly() {
        return false;
    }

    /**
     * 执行工具。
     *
     * <p>出错的两种方式：
     * <ul>
     *   <li>可预期的错误（参数不对、文件不存在）：抛 {@link ToolException}，消息会原样交给模型，让它自己修正。</li>
     *   <li>其他异常：被当作工具内部错误处理，同样不会让回合崩溃。</li>
     * </ul>
     *
     * @param args    已解析并通过 Schema 校验的参数
     * @param context 本次调用的上下文：工作区、取消、进度
     * @return 结果，会作为 tool 消息回填给模型
     */
    ToolResult call(ToolArgs args, ToolContext context) throws Exception;
}
