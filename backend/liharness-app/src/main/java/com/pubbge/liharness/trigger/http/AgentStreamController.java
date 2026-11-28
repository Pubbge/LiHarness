package com.pubbge.liharness.trigger.http;

import com.pubbge.liharness.cases.agent.TurnCase;
import com.pubbge.liharness.cases.agent.TurnRegistry;
import com.pubbge.liharness.cases.settings.ModelCase;
import com.pubbge.liharness.domain.llm.error.LlmException;
import com.pubbge.liharness.trigger.service.stream.SseTurnListener;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 流式对话接口：POST /api/agent/stream，响应是 text/event-stream（SSE）。
 *
 * <p>只负责 HTTP 层面的事：校验参数、占用会话、开 SSE、把回合交给线程池。回合本身在 {@link TurnCase}。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentStreamController {

    private static final Logger log = LoggerFactory.getLogger(AgentStreamController.class);

    /** 请求体 */
    public record StreamRequest(String sessionId, String input) {
    }

    private final ExecutorService turnExecutor;
    private final TurnRegistry turnRegistry;
    private final TurnCase turnCase;

    public AgentStreamController(ExecutorService turnExecutor, TurnRegistry turnRegistry, TurnCase turnCase) {
        this.turnExecutor = turnExecutor;
        this.turnRegistry = turnRegistry;
        this.turnCase = turnCase;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody StreamRequest request) {
        if (request.input() == null || request.input().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "input 不能为空");
        }
        String sessionId = request.sessionId() == null || request.sessionId().isBlank() ? "default" : request.sessionId();
        ModelCase.ActiveModel model;
        try {
            model = turnCase.checkModel();
        } catch (LlmException e) {
            // 模型没配好（第 05 章）：消息里写了该改哪个文件、哪个环境变量，前端直接显示
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        // 409 Conflict：这个会话正在进行一个回合。在开 SSE 之前就拒绝，前端拿到的是普通的错误响应
        TurnRegistry.RunningTurn turn = turnRegistry.tryStart(sessionId).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.CONFLICT, "这个会话正在回答上一个问题，请等它结束或先停止"));

        SseEmitter emitter = new SseEmitter(0L);
        SseTurnListener listener = new SseTurnListener(emitter, turn.cancel());
        // 连接结束（包括前端断开）就取消回合。回合正常结束时也会触发，此时取消已无影响
        emitter.onCompletion(turn.cancel()::cancel);
        emitter.onError(e -> turn.cancel().cancel());

        try {
            turnExecutor.execute(() -> {
                try {
                    turnCase.run(turn, model, request.input(), listener);
                } finally {
                    // 不管回合怎么结束，都要释放会话、关闭连接，否则这个会话再也发不了消息
                    turnRegistry.finish(turn);
                    emitter.complete();
                }
            });
        } catch (RejectedExecutionException e) {
            turnRegistry.finish(turn);
            log.warn("回合线程池已满，拒绝新的回合");
            listener.send("error", Map.of("message", "系统繁忙，请稍后再试", "cancelled", false, "kind", ""));
            emitter.complete();
        }
        return emitter;
    }
}
