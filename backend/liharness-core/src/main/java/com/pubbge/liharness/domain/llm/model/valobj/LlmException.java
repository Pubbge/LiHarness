package com.pubbge.liharness.domain.llm.model.valobj;

/**
 * 模型调用失败。
 *
 * <p>受检异常：模型调用是整个系统里最容易失败的一步（网络、限流、余额、服务过载），
 * 编译器强制调用方处理，免得有人忘了。
 *
 * <p>不同的失败处理方式不同，所以用 {@link Kind} 分类，而不是让调用方去解析错误信息：
 * 能重试的自动重试（第 07 章），不能重试的直接告诉用户该怎么办。
 */
public class LlmException extends Exception {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        /** API Key 错误或无权限 → 提示用户去设置页检查，重试没用 */
        AUTHENTICATION(false),
        /** 请求太频繁（HTTP 429）→ 等一会儿重试 */
        RATE_LIMITED(true),
        /** 服务端过载（如 HTTP 529/503）→ 等一会儿重试 */
        OVERLOADED(true),
        /** 输入超过模型的上下文窗口 → 需要压缩上下文（第 11 章），原样重试没用 */
        CONTEXT_TOO_LONG(false),
        /** 请求格式错误（HTTP 400）→ 是我们的 bug，重试没用 */
        INVALID_REQUEST(false),
        /** 连接失败、超时、读到一半断了 → 重试 */
        NETWORK(true),
        /** 服务端内部错误（HTTP 500）→ 重试 */
        SERVER(true),
        /** 其他 */
        UNKNOWN(false);

        private final boolean retryable;

        Kind(boolean retryable) {
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    private final Kind kind;

    public LlmException(Kind kind, String message) {
        this(kind, message, null);
    }

    public LlmException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind == null ? Kind.UNKNOWN : kind;
    }

    public Kind kind() {
        return kind;
    }

    public boolean retryable() {
        return kind.retryable();
    }
}
