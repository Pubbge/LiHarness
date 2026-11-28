package com.pubbge.liharness.domain.shared.validate;

/** 包内小工具：参数校验。 */
public final class Texts {

    private Texts() {
    }

    public static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }
}
