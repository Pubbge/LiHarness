package com.pubbge.liharness.domain.llm.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pubbge.liharness.domain.llm.adapter.port.ILlmPort;
import com.pubbge.liharness.domain.llm.adapter.port.LlmStream;
import com.pubbge.liharness.domain.llm.model.valobj.FinishReason;
import com.pubbge.liharness.domain.llm.model.valobj.LlmException;
import com.pubbge.liharness.domain.llm.model.valobj.LlmRequest;
import com.pubbge.liharness.domain.llm.model.valobj.LlmResponse;
import com.pubbge.liharness.domain.llm.model.valobj.Message;
import com.pubbge.liharness.domain.llm.model.valobj.StreamChunk;
import com.pubbge.liharness.domain.shared.cancel.CancellationToken;
import com.pubbge.liharness.domain.shared.cancel.CancelledException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class LlmGenerationServiceTest {

    private static final LlmRequest REQUEST = LlmRequest.of("", List.of(Message.user("hi")), List.of());

    /** 只返回固定片段的端口，记下流有没有被关闭 */
    private static final class FixedPort implements ILlmPort {
        final AtomicBoolean closed = new AtomicBoolean();
        final LlmStream inner;

        FixedPort(LlmStream inner) {
            this.inner = inner;
        }

        @Override
        public String id() {
            return "fixed";
        }

        @Override
        public LlmStream stream(LlmRequest request) {
            return new LlmStream() {
                @Override
                public StreamChunk next() throws LlmException {
                    return inner.next();
                }

                @Override
                public void close() {
                    closed.set(true);
                    inner.close();
                }
            };
        }
    }

    @Test
    void observerSeesEveryChunkAndStreamIsClosed() throws Exception {
        FixedPort port = new FixedPort(LlmStreams.of(List.of(
            new StreamChunk.TextDelta("你"), new StreamChunk.TextDelta("好"),
            new StreamChunk.Finish(FinishReason.END_TURN, "m"))));
        List<StreamChunk> seen = new ArrayList<>();

        LlmResponse r = new LlmGenerationService(port).generate(REQUEST, new CancellationToken(), seen::add);

        assertEquals("你好", r.text());
        assertEquals(3, seen.size());
        assertTrue(port.closed.get(), "正常读完也要关流");
    }

    @Test
    void errorsPropagateAndStreamIsStillClosed() {
        FixedPort port = new FixedPort(LlmStreams.failed(new LlmException(LlmException.Kind.RATE_LIMITED, "慢点")));
        LlmException e = assertThrows(LlmException.class,
            () -> new LlmGenerationService(port).generate(REQUEST, new CancellationToken()));
        assertEquals(LlmException.Kind.RATE_LIMITED, e.kind());
        assertTrue(port.closed.get());
    }

    @Test
    void cancellingClosesTheStreamAndWakesUpTheReader() throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        ILlmPort hanging = new ILlmPort() { // 一直不返回片段的模型
            @Override
            public String id() {
                return "hanging";
            }

            @Override
            public LlmStream stream(LlmRequest request) {
                return new LlmStream() {
                    @Override
                    public StreamChunk next() {
                        try {
                            closed.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        throw new CancelledException();
                    }

                    @Override
                    public void close() {
                        closed.countDown();
                    }
                };
            }
        };
        CancellationToken cancel = new CancellationToken();
        new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
                // 测试线程
            }
            cancel.cancel();
        }).start();

        assertThrows(CancelledException.class, () -> new LlmGenerationService(hanging).generate(REQUEST, cancel));
        assertTrue(closed.await(1, TimeUnit.SECONDS));
    }

    @Test
    void alreadyCancelledDoesNotEvenOpenAStream() {
        CancellationToken cancel = new CancellationToken();
        cancel.cancel();
        FixedPort port = new FixedPort(LlmStreams.of(List.of(new StreamChunk.Finish(FinishReason.END_TURN, ""))));
        assertThrows(CancelledException.class, () -> new LlmGenerationService(port).generate(REQUEST, cancel));
        assertTrue(!port.closed.get());
    }
}
