package com.pubbge.liharness.domain.llm.service;

import com.pubbge.liharness.domain.llm.adapter.port.LlmStream;
import com.pubbge.liharness.domain.llm.error.LlmException;
import com.pubbge.liharness.domain.llm.exchange.LlmResponse;
import com.pubbge.liharness.domain.llm.stream.StreamChunk;
import com.pubbge.liharness.domain.llm.tool.ToolCall;
import com.pubbge.liharness.domain.shared.cancel.CancelledException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 构造“片段事先准备好”的响应流。用在：不支持流式的模型服务（拿到完整回复后包成流）、测试替身。
 */
public final class LlmStreams {

    private LlmStreams() {
    }

    /**
     * 把一个完整回复拆成片段：正文一段、每个工具调用一段、用量、结束。
     * 和真实的流经过 {@link ResponseAssembler} 拼回来的结果完全一样。
     */
    public static LlmStream of(LlmResponse response) {
        List<StreamChunk> chunks = new ArrayList<>();
        if (!response.text().isEmpty()) {
            chunks.add(new StreamChunk.TextDelta(response.text()));
        }
        List<ToolCall> calls = response.toolCalls();
        for (int i = 0; i < calls.size(); i++) {
            ToolCall c = calls.get(i);
            chunks.add(new StreamChunk.ToolCallDelta(i, c.id(), c.name(), c.argumentsJson()));
        }
        chunks.add(new StreamChunk.Usage(response.usage()));
        chunks.add(new StreamChunk.Finish(response.finishReason(), response.model()));
        return of(chunks);
    }

    /** 按给定的片段依次返回。最后一个必须是 Finish */
    public static LlmStream of(List<StreamChunk> chunks) {
        if (chunks.isEmpty() || !(chunks.get(chunks.size() - 1) instanceof StreamChunk.Finish)) {
            throw new IllegalArgumentException("最后一个片段必须是 Finish");
        }
        return new FixedStream(new ArrayDeque<>(chunks), null);
    }

    /** 调用失败：第一次 next() 就抛出这个异常 */
    public static LlmStream failed(LlmException error) {
        return new FixedStream(new ArrayDeque<>(), error);
    }

    private static final class FixedStream implements LlmStream {

        private final Deque<StreamChunk> chunks;
        private final LlmException error;
        private volatile boolean closed;
        private boolean finished;

        FixedStream(Deque<StreamChunk> chunks, LlmException error) {
            this.chunks = chunks;
            this.error = error;
        }

        @Override
        public StreamChunk next() throws LlmException {
            if (closed) {
                throw new CancelledException();
            }
            if (error != null) {
                throw error;
            }
            if (finished) {
                throw new IllegalStateException("流已经结束");
            }
            StreamChunk c = chunks.removeFirst();
            finished = c instanceof StreamChunk.Finish;
            return c;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
