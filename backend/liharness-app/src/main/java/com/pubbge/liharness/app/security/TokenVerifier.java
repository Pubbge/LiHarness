package com.pubbge.liharness.app.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 校验 Authorization 头里的 Bearer 令牌。 */
public final class TokenVerifier {

    private static final String PREFIX = "Bearer ";

    private final byte[] expected;

    public TokenVerifier(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("未配置启动令牌 liharness.token，拒绝启动");
        }
        this.expected = token.getBytes(StandardCharsets.UTF_8);
    }

    /** authorizationHeader 形如 "Bearer xxxx"；为 null 或格式不对都返回 false。 */
    public boolean verify(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(PREFIX)) {
            return false;
        }
        byte[] actual = authorizationHeader.substring(PREFIX.length()).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }
}
