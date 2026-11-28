package com.pubbge.liharness.infrastructure.adapter.llm.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.pubbge.liharness.domain.llm.error.LlmException.Kind;
import com.pubbge.liharness.domain.llm.error.LlmException;
import com.pubbge.liharness.domain.shared.json.Json;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * 把 HTTP 错误翻译成分类好的 {@link LlmException}（第 04 章的错误分类表）。
 */
final class OpenAiErrors {

    private OpenAiErrors() {
    }

    static LlmException fromHttp(int status, String body, Optional<String> retryAfterHeader) {
        String message = extractMessage(body);
        String lower = (message + " " + body).toLowerCase(Locale.ROOT);
        Kind kind = switch (status) {
            case 401, 403 -> Kind.AUTHENTICATION;
            case 402 -> Kind.QUOTA_EXCEEDED;
            case 408 -> Kind.NETWORK;
            case 413 -> Kind.CONTEXT_TOO_LONG;
            case 429 -> lower.contains("insufficient_quota") || lower.contains("quota") ? Kind.QUOTA_EXCEEDED : Kind.RATE_LIMITED;
            case 503, 529 -> Kind.OVERLOADED;
            default -> {
                if (status >= 500) {
                    yield Kind.SERVER;
                }
                // 400 里最重要的一种：上下文太长。各家措辞不同，只能按关键词识别
                if (lower.contains("context_length") || lower.contains("context length")
                    || lower.contains("maximum context") || lower.contains("too many tokens")
                    || lower.contains("prompt is too long")) {
                    yield Kind.CONTEXT_TOO_LONG;
                }
                yield Kind.INVALID_REQUEST;
            }
        };
        String hint = switch (kind) {
            case AUTHENTICATION -> "API Key 无效或没有权限，请检查配置的 Key";
            case QUOTA_EXCEEDED -> "账户余额不足或额度已用完";
            case RATE_LIMITED -> "请求太频繁，被限流了";
            case OVERLOADED -> "模型服务繁忙";
            case CONTEXT_TOO_LONG -> "对话太长，超出了模型的上下文窗口";
            default -> "模型服务返回错误";
        };
        Duration retryAfter = retryAfterHeader.flatMap(OpenAiErrors::parseRetryAfter).orElse(null);
        return new LlmException(kind, hint + "（HTTP " + status + (message.isEmpty() ? "" : "：" + message) + "）",
            null, retryAfter);
    }

    /** 错误体通常是 {"error":{"message":"..."}}；不是 JSON 就截取原文 */
    static String extractMessage(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonNode node = Json.readTree(body);
            JsonNode msg = node.path("error").path("message");
            if (msg.isTextual()) {
                return msg.asText();
            }
            if (node.path("message").isTextual()) {
                return node.path("message").asText();
            }
        } catch (Exception ignored) {
            // 不是 JSON
        }
        String s = body.strip();
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    /** Retry-After 可以是秒数，也可以是一个 HTTP 日期。这里只处理常见的秒数 */
    static Optional<Duration> parseRetryAfter(String value) {
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds >= 0 ? Optional.of(Duration.ofSeconds(seconds)) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
