package com.pubbge.liharness.app.agent;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 流式对话接口：POST /api/agent/stream，响应是 text/event-stream（SSE）。
 *
 * <p>第 03 章还没有模型，这里先推一段假数据，目的是把“后端一边算一边推、前端一边收一边显示”的通道打通。
 * 第 06 章会把 {@link #demoTurn} 换成真正的 Agent 回合。
 *
 * <p>一次请求的过程：
 * <pre>
 * 前端 POST ──► Tomcat 请求线程：创建 SseEmitter，把回合交给线程池，立刻 return emitter（连接保持打开）
 *                                     │
 *                 回合线程池里的线程 ──┴─► send(turn_started) → send(delta) × N → send(done) → complete()
 * </pre>
 */
@RestController
@RequestMapping("/api/agent")
public class AgentStreamController {

    private static final Logger log = LoggerFactory.getLogger(AgentStreamController.class);

    /** 请求体。sessionId 第 12 章做多会话时才真正用上，现在前端固定传 "default" */
    public record StreamRequest(String sessionId, String input) {
    }

    /** 回合线程池（config/ExecutorConfig 里定义的那个 Bean） */
    private final ExecutorService turnExecutor;

    public AgentStreamController(ExecutorService turnExecutor) {
        this.turnExecutor = turnExecutor;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody StreamRequest request) {
        // 参数 0 表示“不设超时”。回合可能跑好几分钟，什么时候结束由回合自己决定（调用 complete()）
        SseEmitter emitter = new SseEmitter(0L);

        // 前端断开连接（关闭窗口、刷新页面、点了停止）时，这两个回调会被触发。
        // 用一个标志位通知回合线程“没人在听了，别再推了”，避免白白浪费资源。
        // AtomicBoolean：一个线程写、另一个线程读，需要保证可见性。
        AtomicBoolean clientGone = new AtomicBoolean(false);
        emitter.onCompletion(() -> clientGone.set(true));
        emitter.onError(e -> clientGone.set(true));

        try {
            // 交给回合线程池执行，当前的 Tomcat 请求线程马上就能返回
            turnExecutor.execute(() -> demoTurn(emitter, request, clientGone));
        } catch (RejectedExecutionException e) {
            // 线程池和等待队列都满了：明确告诉前端“系统繁忙”，而不是让请求一直挂着
            log.warn("回合线程池已满，拒绝新的回合");
            send(emitter, "error", Map.of("message", "系统繁忙，请稍后再试", "cancelled", false));
            emitter.complete();
        }
        return emitter;
    }

    /** 演示回合：把一段固定文本一个字一个字推给前端，模拟模型的流式输出 */
    private void demoTurn(SseEmitter emitter, StreamRequest request, AtomicBoolean clientGone) {
        String turnId = UUID.randomUUID().toString();
        log.info("演示回合开始 turnId={}", turnId);

        // String.valueOf：sessionId 可能是 null，Map.of 不允许放 null
        send(emitter, "turn_started", Map.of("turnId", turnId, "sessionId", String.valueOf(request.sessionId())));

        String answer = "这是后端推送的演示回答，你刚才说的是：" + request.input();

        // 按“码点”（code point）切分而不是按 char 切：
        // 少数汉字和所有 emoji 由两个 char 组成，按 char 切会切出半个字符，前端显示成乱码。
        for (int i = 0; i < answer.length() && !stopped(clientGone); ) {
            int cp = answer.codePointAt(i);
            send(emitter, "delta", Map.of("text", new String(Character.toChars(cp))));
            i += Character.charCount(cp);
            sleep(30); // 模拟模型“一个字一个字往外吐”的速度
        }

        if (stopped(clientGone)) {
            log.info("前端已断开或应用正在关闭，演示回合提前结束 turnId={}", turnId);
            emitter.complete();
            return;
        }
        send(emitter, "done", Map.of("turnId", turnId, "answer", answer, "model", "demo"));
        emitter.complete(); // 告诉 Spring：这个响应写完了，可以关闭连接
        log.info("演示回合结束 turnId={}", turnId);
    }

    /**
     * 推送一条 SSE 事件。最终在网络上是这样两行加一个空行：
     * <pre>
     * event:delta
     * data:{"text":"你"}
     * </pre>
     * data 用 JSON 格式，前端解析后和 event 名合并成一个事件对象。
     */
    static void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            // 前端已断开（IOException），或 emitter 已经结束（IllegalStateException）：忽略即可，
            // 回合会通过 clientGone 标志停下来
        }
    }

    /** 该停了吗：前端断开了，或者线程被中断了（应用关闭时线程池会中断所有回合线程） */
    private static boolean stopped(AtomicBoolean clientGone) {
        return clientGone.get() || Thread.currentThread().isInterrupted();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            // 线程被中断（比如应用正在关闭，线程池调用了 shutdownNow）：
            // 恢复中断标志，让上层代码也能看到“我被要求停下”
            Thread.currentThread().interrupt();
        }
    }
}
