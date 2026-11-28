package com.pubbge.liharness.domain.tool.model.valobj;

import com.pubbge.liharness.domain.shared.cancel.CancellationToken;
import java.nio.file.Path;
import java.util.function.BiConsumer;

/**
 * 一次回合里所有工具调用共享的环境：哪个会话、哪个回合、工作区在哪、取消令牌。
 *
 * @param progress 进度回调：(toolCallId, 消息)。没有界面时传 {@link #NO_PROGRESS}
 */
public record ToolCallScope(
        String sessionId,
        String turnId,
        Path workspace,
        CancellationToken cancel,
        BiConsumer<String, String> progress) {

    public static final BiConsumer<String, String> NO_PROGRESS = (id, msg) -> { };

    public ToolCallScope {
        if (cancel == null) {
            throw new IllegalArgumentException("cancel 不能为空");
        }
        progress = progress == null ? NO_PROGRESS : progress;
    }
}
