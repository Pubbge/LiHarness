package com.pubbge.liharness.domain.llm.service;

import com.pubbge.liharness.domain.llm.adapter.port.LlmChatModelAdapter;
import com.pubbge.liharness.domain.llm.adapter.port.LlmStream;
import com.pubbge.liharness.domain.llm.error.LlmException;
import com.pubbge.liharness.domain.llm.exchange.LlmRequest;
import com.pubbge.liharness.domain.llm.exchange.LlmResponse;
import com.pubbge.liharness.domain.llm.stream.StreamChunk;
import com.pubbge.liharness.domain.shared.cancel.CancellationToken;
import java.util.function.Consumer;

/**
 * 生成一次完整回复：打开流、读到 Finish、拼装、关闭。这是所有“调用模型”的地方共用的一段流程。
 *
 * <pre>
 *  LlmChatModelAdapter.stream() ──► LlmStream ──next()──► 片段 ──► observer（比如推给界面）
 *                           ▲                    └──► ResponseAssembler ──► LlmResponse
 *                           └── close() ◄── 取消令牌
 * </pre>
 *
 * <p>为什么需要它：端口只给流，而调用方要的通常是“完整回复 + 顺便看一眼片段”。这段读流、拼装、
 * 挂取消、保证关闭的代码如果在 Agent Loop、摘要器、模型测试里各写一遍，迟早有一处忘了关流。
 * 和 deepseek-harness-java 的 LlmGenerationService 是同一个角色。
 *
 * <p>取消令牌只出现在这里，不出现在端口上：取消的实现就是一行 {@code cancel.onCancel(stream::close)}。
 */
public final class LlmGenerationService {

    private final LlmChatModelAdapter llmChatModelAdapter;

    public LlmGenerationService(LlmChatModelAdapter llm) {
        this.llmChatModelAdapter = llm;
    }

    /** 不关心中间片段，只要完整回复 */
    public LlmResponse generate(LlmRequest request, CancellationToken cancel) throws LlmException {
        return generate(request, cancel, chunk -> { });
    }

    /**
     * 生成完整回复，每收到一个片段先交给 observer。
     *
     * @param observer 在读流的线程上同步调用，要快；它抛出的异常会中止这次调用
     * @throws LlmException                                            模型调用失败
     * @throws com.pubbge.liharness.domain.shared.cancel.CancelledException 被取消
     */
    public LlmResponse generate(LlmRequest request, CancellationToken cancel, Consumer<StreamChunk> observer)
            throws LlmException {
        cancel.throwIfCancelled();
        ResponseAssembler assembler = new ResponseAssembler();
        LlmStream stream = llmChatModelAdapter.stream(request);
        // 取消 = 关闭流：正在阻塞的 next() 会立刻抛 CancelledException
        CancellationToken.Registration onCancel = cancel.onCancel(stream::close);
        try {
            while (!assembler.finished()) {
                StreamChunk chunk = stream.next();
                assembler.push(chunk);
                observer.accept(chunk);
            }
            return assembler.response();
        } finally {
            onCancel.close();
            stream.close();
        }
    }
}
