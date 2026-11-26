package com.pubbge.liharness.domain.llm.model.valobj;

/** 包内小工具：参数校验。 */
final class Texts {

    private Texts() {
    }

    static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }
}
