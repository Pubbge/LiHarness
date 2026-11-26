package com.pubbge.liharness.trigger.http;

import com.pubbge.liharness.domain.llm.adapter.port.ILlmPort;
import com.pubbge.liharness.domain.llm.model.valobj.LlmException;
import com.pubbge.liharness.domain.llm.model.valobj.LlmRequest;
import com.pubbge.liharness.domain.llm.model.valobj.LlmResponse;
import com.pubbge.liharness.domain.llm.model.valobj.Message;
import com.pubbge.liharness.domain.llm.model.valobj.StreamChunk;
import com.pubbge.liharness.domain.llm.service.LlmGenerationService;
import com.pubbge.liharness.domain.shared.cancel.CancellationToken;
import com.pubbge.liharness.domain.shared.cancel.CancelledException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * <p>第 04 章：单次调用模型，把模型的流式输出转推给前端。还没有工具执行，也没有多轮历史。
 * 第 05 章会把 {@link #runTurn} 换成 Agent Loop。
 *
 * <pre>
 *   ILlmPort ──LlmStream──► LlmGenerationService ──TextDelta──► SseEmitter.send() ──► 前端
 *       ▲                         │（读流、拼装、关流）                                  │
 *       └──── close() ◄── 取消令牌 ◄──────────── 连接断开 / 推送失败 ◄─────────────────────┘
 * </pre>
 *
 * <p>它属于 trigger 层：只管 HTTP 和 SSE 协议，不写业务规则。读流、拼装这些规则在领域服务里。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentStreamController {

    private static final Logger log = LoggerFactory.getLogger(AgentStreamController.class);

    /** 请求体。sessionId 第 10 章做会话持久化时才真正用上，现在前端固定传 "default" */
    public record StreamRequest(String sessionId, String input) {
    }

    private final ExecutorService turnExecutor;
    private final ILlmPort llm;

    public AgentStreamController(ExecutorService turnExecutor, ILlmPort llm) {
        this.turnExecutor = turnExecutor;
        this.llm = llm;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody StreamRequest request) {
        // 参数错误在进入线程池之前就拦下，返回普通的 400，而不是开了 SSE 再推一个 error
        if (request.input() == null || request.input().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "input 不能为空");
        }

        SseEmitter emitter = new SseEmitter(0L);
        // 一个回合一个取消令牌。连接结束（不管是正常完成、出错还是前端断开），都取消回合
        CancellationToken cancel = new CancellationToken();
        emitter.onCompletion(cancel::cancel);
        emitter.onError(e -> cancel.cancel());

        try {
            turnExecutor.execute(() -> runTurn(emitter, request, cancel));
        } catch (RejectedExecutionException e) {
            log.warn("回合线程池已满，拒绝新的回合");
            send(emitter, cancel, "error", Map.of("message", "系统繁忙，请稍后再试", "cancelled", false));
            emitter.complete();
        }
        return emitter;
    }

    private void runTurn(SseEmitter emitter, StreamRequest request, CancellationToken cancel) {
        String turnId = UUID.randomUUID().toString();
        log.info("回合开始 turnId={} model={}", turnId, llm.id());
        send(emitter, cancel, "turn_started", Map.of("turnId", turnId, "sessionId", String.valueOf(request.sessionId())));

        LlmRequest llmRequest = LlmRequest.of("", List.of(Message.user(request.input())), List.of());
        try {
            // 取消、关流、拼装都在领域服务里；这里只关心“每段正文推给前端”
            LlmResponse response = new LlmGenerationService(llm).generate(llmRequest, cancel, chunk -> {
                if (chunk instanceof StreamChunk.TextDelta delta) {
                    send(emitter, cancel, "delta", Map.of("text", delta.text()));
                }
            });
            send(emitter, cancel, "done", Map.of(
                "turnId", turnId,
                "answer", response.text(),
                "model", response.model(),
                "totalTokens", response.usage().total()));
            log.info("回合结束 turnId={} finish={} usage={}", turnId, response.finishReason(), response.usage());
        } catch (CancelledException e) {
            // 用户停止或前端断开：正常情况，不算错误。连接多半已经断了，不用再推事件
            log.info("回合被取消 turnId={}", turnId);
        } catch (LlmException e) {
            log.warn("模型调用失败 turnId={} kind={}: {}", turnId, e.kind(), e.getMessage());
            send(emitter, cancel, "error", Map.of("message", String.valueOf(e.getMessage()), "cancelled", false));
        } catch (RuntimeException e) {
            // 兜底：任何意外都不能让连接一直挂着，前端会永远显示“正在输入”
            log.error("回合异常 turnId={}", turnId, e);
            send(emitter, cancel, "error", Map.of("message", "内部错误：" + e.getMessage(), "cancelled", false));
        } finally {
            emitter.complete();
        }
    }

    /**
     * 推送一条 SSE 事件。推送失败说明前端已经断开，此时取消回合：没人在听了，模型就别再生成了，省钱。
     */
    private static void send(SseEmitter emitter, CancellationToken cancel, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            cancel.cancel();
        }
    }
}
