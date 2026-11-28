package com.pubbge.liharness.domain.tool.service;

import com.pubbge.liharness.domain.spi.tool.ToolContext;
import com.pubbge.liharness.domain.tool.model.valobj.ToolCallScope;
import java.nio.file.Path;

/** {@link ToolContext} 的默认实现：把一次调用的 id 和回合共享的 {@link ToolCallScope} 拼在一起 */
final class DefaultToolContext implements ToolContext {

    private final ToolCallScope scope;
    private final String toolCallId;

    DefaultToolContext(ToolCallScope scope, String toolCallId) {
        this.scope = scope;
        this.toolCallId = toolCallId;
    }

    @Override
    public String sessionId() {
        return scope.sessionId();
    }

    @Override
    public String turnId() {
        return scope.turnId();
    }

    @Override
    public String toolCallId() {
        return toolCallId;
    }

    @Override
    public Path workspace() {
        return scope.workspace();
    }

    @Override
    public boolean isCancelled() {
        return scope.cancel().isCancelled();
    }

    @Override
    public Registration onCancel(Runnable action) {
        return scope.cancel().onCancel(action)::close;
    }

    @Override
    public void reportProgress(String message) {
        scope.progress().accept(toolCallId, message);
    }
}
