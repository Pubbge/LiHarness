package com.pubbge.liharness.infrastructure.adapter.llm.openai;

import com.pubbge.liharness.domain.llm.adapter.port.LlmChatModelAdapter;
import com.pubbge.liharness.domain.llm.adapter.port.LlmStream;
import com.pubbge.liharness.domain.llm.error.LlmException.Kind;
import com.pubbge.liharness.domain.llm.error.LlmException;
import com.pubbge.liharness.domain.llm.exchange.LlmRequest;
import com.pubbge.liharness.domain.llm.stream.StreamChunk;
import com.pubbge.liharness.domain.shared.cancel.CancelledException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OpenAI Chat Completions 协议的模型适配器，适用于所有兼容这个协议的服务。
 *
 * <p>线程模型（这是本类最关键的设计）：
 * <pre>
 *  HttpClient 的线程（推）                           回合线程（拉）
 *  ─────────────────────                            ──────────────────────────
 *  收到一行      ──► LineQueue.onNext   ──┐
 *  流结束        ──► LineQueue.onComplete ─┤
 *  请求失败/非2xx ──► future 完成         ─┼──► 队列 ──► stream.next() 阻塞等待 ──► 解析 ──► 返回事件
 *  调用方 close() ──► 放一个 CLOSED 标记  ─┘
 * </pre>
 * HttpClient 在自己的线程上“推”数据，调用方在自己的线程上“拉”数据，中间用一个阻塞队列衔接。
 * 所有需要唤醒回合线程的情况（新数据、出错、结束、被关闭）都变成往队列里放一样东西，
 * 所以 next() 只需要一个 {@code queue.poll(空闲超时)}，没有轮询、没有忙等。
 *
 * <p>适配器只做两件事：HTTP 和“翻译”（{@link OpenAiChunkParser} 把 JSON 翻译成 StreamChunk）。
 * 拼成完整回复是领域层 ResponseAssembler 的事。
 */
public final class OpenAiCompatibleAdapter implements LlmChatModelAdapter {

    /** 从发出请求到收到响应头，最长等多久。连接都建不起来时，不必等满空闲超时 */
    static final Duration RESPONSE_HEADERS_TIMEOUT = Duration.ofSeconds(60);

    private final OpenAiConfig config;
    private final HttpClient http;

    public OpenAiCompatibleAdapter(OpenAiConfig config, HttpClient http) {
        this.config = config;
        this.http = http;
    }

    @Override
    public String id() {
        return config.model();
    }

    /**
     * 发出请求，立即返回（不等响应）。连接、认证、限流等错误都会在第一次 next() 时抛出。
     */
    @Override
    public LlmStream stream(LlmRequest request) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(config.chatCompletionsUri())
            .timeout(RESPONSE_HEADERS_TIMEOUT)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(OpenAiRequests.body(request, config.model()), StandardCharsets.UTF_8));
        if (config.apiKey() != null) {
            // 本机的 Ollama 这类服务不需要 Key：不发这个头，而不是发一个 "Bearer null"
            builder.header("Authorization", "Bearer " + config.apiKey());
        }
        return new OpenAiStream(builder.build());
    }

    /** 队列里的一项 */
    private record Item(Kind kind, String line, Throwable error) {
        enum Kind { LINE, LINE_ERROR, END, REQUEST_DONE, CLOSED }

        static final Item END = new Item(Kind.END, null, null);
        static final Item REQUEST_DONE = new Item(Kind.REQUEST_DONE, null, null);
        static final Item CLOSED = new Item(Kind.CLOSED, null, null);
    }

    /** 一次调用的响应流 */
    private final class OpenAiStream implements LlmStream {

        private final BlockingQueue<Item> queue = new LinkedBlockingQueue<>();
        private final LineSubscriber lines = new LineSubscriber(queue);
        private final AtomicInteger status = new AtomicInteger();
        private final AtomicReference<Optional<String>> retryAfter = new AtomicReference<>(Optional.empty());
        private final CompletableFuture<HttpResponse<String>> future;
        private final OpenAiChunkParser parser = new OpenAiChunkParser();
        /** 一行可能翻译出好几个片段（比如正文 + 工具调用），先放这里，一个一个交出去 */
        private final Deque<StreamChunk> pending = new ArrayDeque<>();
        private volatile boolean closed;
        private boolean finished;

        OpenAiStream(HttpRequest httpRequest) {
            // 响应头到达时决定怎么读响应体：2xx 一行一行交给队列；否则整个读成字符串，用来生成错误信息
            HttpResponse.BodyHandler<String> handler = info -> {
                status.set(info.statusCode());
                retryAfter.set(info.headers().firstValue("Retry-After"));
                // 行分隔符传 null：\n、\r\n、\r 都算换行，各家服务用哪种都能正确切分
                return info.statusCode() / 100 == 2
                    ? HttpResponse.BodySubscribers.fromLineSubscriber(lines, s -> "", StandardCharsets.UTF_8, null)
                    : HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
            };
            future = http.sendAsync(httpRequest, handler);
            // 请求结束（成功、失败、非 2xx）也通知一下回合线程
            future.whenComplete((r, e) -> queue.add(Item.REQUEST_DONE));
        }

        @Override
        public StreamChunk next() throws LlmException {
            if (finished) {
                throw new IllegalStateException("流已经结束");
            }
            while (true) {
                if (closed) {
                    throw new CancelledException();
                }
                if (!pending.isEmpty()) {
                    StreamChunk chunk = pending.removeFirst();
                    finished = chunk instanceof StreamChunk.Finish;
                    return chunk;
                }
                Item item = take();
                if (closed) {
                    // 被关闭以后，队列里可能还有一个“连接中断”的错误（正是关闭本身导致的），不能把它当成网络故障
                    throw new CancelledException();
                }
                switch (item.kind()) {
                    case CLOSED -> throw new CancelledException();
                    case LINE_ERROR -> throw new LlmException(Kind.NETWORK,
                        "读取模型响应时连接中断：" + item.error().getMessage(), item.error());
                    case END -> pending.addAll(parser.finish()); // 没发 [DONE] 的服务：连接正常结束就算结束
                    case REQUEST_DONE -> failIfRequestFailed();   // 成功的话 END 已经或者马上就到
                    case LINE -> pending.addAll(parser.parse(item.line()));
                }
            }
        }

        /** 带空闲超时地等下一项：模型长时间一个字节都不发，就认为连接卡死了 */
        private Item take() throws LlmException {
            try {
                Item item = queue.poll(config.idleTimeout().toMillis(), TimeUnit.MILLISECONDS);
                if (item == null) {
                    throw new LlmException(Kind.NETWORK, "模型服务超过 " + config.idleTimeout().toSeconds() + " 秒没有返回数据");
                }
                return item;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancelledException();
            }
        }

        /** 请求已经结束：如果是失败（网络错误或非 2xx），抛出分类好的异常；成功则什么也不做 */
        private void failIfRequestFailed() throws LlmException {
            HttpResponse<String> response;
            try {
                response = future.join();
            } catch (CancellationException e) {
                throw new CancelledException();
            } catch (CompletionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (cause instanceof HttpTimeoutException) {
                    throw new LlmException(Kind.NETWORK, "连接模型服务超时", cause);
                }
                throw new LlmException(Kind.NETWORK, "无法连接模型服务：" + cause.getClass().getSimpleName()
                    + (cause.getMessage() == null ? "" : "（" + cause.getMessage() + "）"), cause);
            }
            if (status.get() / 100 != 2) {
                throw OpenAiErrors.fromHttp(status.get(), response.body(), retryAfter.get());
            }
        }

        /**
         * 中止：取消 HTTP 请求和订阅（连接释放，服务端随之停止生成、不再计费），
         * 再往队列里放一个 CLOSED，让阻塞在 take() 上的回合线程立刻醒来。
         */
        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            lines.cancel();
            future.cancel(true);
            queue.add(Item.CLOSED);
        }
    }

    /** 在 HttpClient 的线程上接收响应体的每一行，放进队列 */
    private static final class LineSubscriber implements Flow.Subscriber<String> {

        private final BlockingQueue<Item> queue;
        private volatile Flow.Subscription subscription;
        private volatile boolean cancelled;

        LineSubscriber(BlockingQueue<Item> queue) {
            this.queue = queue;
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            if (cancelled) {
                s.cancel();
            } else {
                s.request(Long.MAX_VALUE); // 文本流不大，不需要背压，全部要
            }
        }

        @Override
        public void onNext(String line) {
            queue.add(new Item(Item.Kind.LINE, line, null));
        }

        @Override
        public void onError(Throwable t) {
            queue.add(new Item(Item.Kind.LINE_ERROR, null, t));
        }

        @Override
        public void onComplete() {
            queue.add(Item.END);
        }

        void cancel() {
            cancelled = true;
            Flow.Subscription s = subscription;
            if (s != null) {
                s.cancel();
            }
        }
    }
}
