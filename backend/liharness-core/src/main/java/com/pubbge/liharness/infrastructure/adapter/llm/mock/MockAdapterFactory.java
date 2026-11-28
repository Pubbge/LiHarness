package com.pubbge.liharness.infrastructure.adapter.llm.mock;

import com.pubbge.liharness.domain.llm.adapter.port.ILlmAdapterFactory;
import com.pubbge.liharness.domain.llm.adapter.port.LlmChatModelAdapter;
import com.pubbge.liharness.domain.llm.provider.ModelProfile;

/** 协议 {@code mock}：不联网的 {@link MockLlmAdapter} */
public final class MockAdapterFactory implements ILlmAdapterFactory {

    @Override
    public String protocol() {
        return "mock";
    }

    @Override
    public LlmChatModelAdapter create(ModelProfile profile, String apiKey) {
        return new MockLlmAdapter();
    }
}
