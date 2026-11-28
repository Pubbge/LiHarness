package com.pubbge.liharness.infrastructure.adapter.llm.openai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 用 JDK 自带的 HttpServer 假扮一个 OpenAI 兼容服务。测试不联网、不花钱、结果可控。
 */
final class FakeOpenAiServer implements AutoCloseable {

    /** 怎么回应一次请求 */
    interface Handler {
        void handle(HttpExchange exchange) throws IOException, InterruptedException;
    }

    final List<String> requestBodies = new CopyOnWriteArrayList<>();
    final List<String> authHeaders = new CopyOnWriteArrayList<>();
    private final HttpServer server;

    FakeOpenAiServer(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            try {
                handler.handle(exchange);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // 客户端断开
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    /** 以 SSE 格式逐行发送，每行之间停顿 pauseMs 毫秒 */
    static void sse(HttpExchange ex, long pauseMs, String... dataLines) throws IOException, InterruptedException {
        ex.getResponseHeaders().add("Content-Type", "text/event-stream");
        ex.sendResponseHeaders(200, 0);
        OutputStream out = ex.getResponseBody();
        for (String d : dataLines) {
            out.write(("data: " + d + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            if (pauseMs > 0) {
                Thread.sleep(pauseMs);
            }
        }
    }

    static void error(HttpExchange ex, int status, String body, String retryAfter) throws IOException {
        if (retryAfter != null) {
            ex.getResponseHeaders().add("Retry-After", retryAfter);
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
