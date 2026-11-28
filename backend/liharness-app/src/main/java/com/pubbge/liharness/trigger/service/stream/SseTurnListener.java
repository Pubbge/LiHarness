package com.pubbge.liharness.trigger.service.stream;

import com.pubbge.liharness.domain.agent.contract.outbound.ITurnEventListener;
import com.pubbge.liharness.domain.agent.model.valobj.TurnEvent;
import com.pubbge.liharness.domain.agent.model.valobj.TurnOutcome;
import com.pubbge.liharness.domain.agent.model.valobj.TurnResult;
import com.pubbge.liharness.domain.shared.cancel.CancellationToken;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 把领域层的回合事件（TurnEvent）翻译成 SSE 事件推给前端。
 *
 * <p>领域层不知道 SSE 的存在，SSE 的事件名和字段格式是后端和前端之间的协议约定，所以翻译放在 trigger 层。
 * 以后换成 WebSocket 或者命令行界面，只需要再写一个 ITurnEventListener，core 一行不改。
 */
public final class SseTurnListener implements ITurnEventListener {

    /** 工具结果在界面上只显示前面一部分，完整内容在会话历史里 */
    private static final int MAX_RESULT_PREVIEW = 2000;

    private final SseEmitter emitter;
    private final CancellationToken cancel;

    public SseTurnListener(SseEmitter emitter, CancellationToken cancel) {
        this.emitter = emitter;
        this.cancel = cancel;
    }

    @Override
    public void onEvent(TurnEvent event) {
        // Java 17 的 switch 还不支持按类型匹配（Java 21 才正式支持），所以用 instanceof 链。
        // 最后的 else 保证：以后新增了事件类型却忘了在这里处理，第一次运行就会暴露出来
        if (event instanceof TurnEvent.TurnStarted e) {
            send("turn_started", Map.of("turnId", e.turnId(), "sessionId", e.sessionId(), "model", e.modelId()));
        } else if (event instanceof TurnEvent.TextDelta e) {
            send("delta", Map.of("text", e.text()));
        } else if (event instanceof TurnEvent.ToolCallStarted e) {
            send("tool_call", Map.of("callId", e.call().id(), "name", e.call().name(), "arguments", e.call().argumentsJson()));
        } else if (event instanceof TurnEvent.ToolProgress e) {
            send("tool_progress", Map.of("callId", e.toolCallId(), "message", e.message()));
        } else if (event instanceof TurnEvent.ToolCallFinished e) {
            send("tool_result", Map.of(
                "callId", e.call().id(),
                "name", e.call().name(),
                "ok", !e.result().error(),
                "content", preview(e.result().content()),
                "durationMs", e.durationMillis()));
        } else if (event instanceof TurnEvent.TurnFinished e) {
            finished(e.result());
        } else {
            throw new IllegalStateException("未处理的回合事件：" + event.getClass().getSimpleName());
        }
    }

    private void finished(TurnResult r) {
        if (r.outcome() == TurnOutcome.CANCELLED || r.outcome() == TurnOutcome.FAILED) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("turnId", r.turnId());
            data.put("message", r.outcome() == TurnOutcome.CANCELLED ? "已停止" : String.valueOf(r.errorMessage()));
            data.put("cancelled", r.outcome() == TurnOutcome.CANCELLED);
            data.put("kind", r.errorKind() == null ? "" : r.errorKind().name());
            send("error", data);
            return;
        }
        // COMPLETED、MAX_STEPS、MAX_TOKENS 都算“正常结束”，后两种带一条提示
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("turnId", r.turnId());
        data.put("outcome", r.outcome().name());
        data.put("answer", r.answer());
        data.put("notice", r.errorMessage() == null ? "" : r.errorMessage());
        data.put("steps", r.steps());
        data.put("inputTokens", r.usage().inputTokens());
        data.put("outputTokens", r.usage().outputTokens());
        data.put("totalTokens", r.usage().total());
        send("done", data);
    }

    /** 推送一条事件。失败说明前端已断开：取消回合，没人在听了就别再花钱调模型 */
    public void send(String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            cancel.cancel();
        }
    }

    private static String preview(String s) {
        return s.length() <= MAX_RESULT_PREVIEW ? s : s.substring(0, MAX_RESULT_PREVIEW) + "\n…（共 " + s.length() + " 字）";
    }
}
