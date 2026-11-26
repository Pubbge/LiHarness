package com.pubbge.liharness.infrastructure.adapter.llm.mock;

import com.pubbge.liharness.domain.llm.adapter.port.ILlmPort;
import com.pubbge.liharness.domain.llm.adapter.port.LlmStream;
import com.pubbge.liharness.domain.llm.model.valobj.FinishReason;
import com.pubbge.liharness.domain.llm.model.valobj.LlmException;
import com.pubbge.liharness.domain.llm.model.valobj.LlmRequest;
import com.pubbge.liharness.domain.llm.model.valobj.LlmResponse;
import com.pubbge.liharness.domain.llm.model.valobj.Message;
import com.pubbge.liharness.domain.llm.model.valobj.Role;
import com.pubbge.liharness.domain.llm.model.valobj.StreamChunk;
import com.pubbge.liharness.domain.llm.model.valobj.TokenUsage;
import com.pubbge.liharness.domain.llm.model.valobj.ToolCall;
import com.pubbge.liharness.domain.llm.service.LlmStreams;
import com.pubbge.liharness.domain.shared.cancel.CancelledException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 不联网的假模型。按固定规则回答，行为完全可预测：
 * <ol>
 *   <li>最后一条是工具结果 → 把结果组织成最终回答（END_TURN）</li>
 *   <li>用户问时间，且请求里提供了 get_time 工具 → 请求调用 get_time（TOOL_USE）</li>
 *   <li>用户输入以 {@code /fail} 开头 → 调用失败（LlmException），用来演练错误处理</li>
 *   <li>其他 → 复述用户输入（END_TURN）</li>
 * </ol>
 * 文本按字符一个一个地流式输出，每个字之间停顿一下，模拟真实模型的速度。
 *
 * <p>它是端口 {@link ILlmPort} 的一个实现，所以放在 infrastructure.adapter.llm 下，和第 07 章的 OpenAI
 * 适配器并列。领域层只认端口，不知道背后是 Mock 还是真模型。
 */
public final class MockLlmAdapter implements ILlmPort {

    public static final String ID = "mock";
    static final String TIME_TOOL = "get_time";

    /** 两个字符之间的间隔。界面演示用 30ms 左右看得清，测试用 Duration.ZERO */
    private final Duration delayPerChar;
    /** 用来生成工具调用 id。真实模型的 id 是随机串，这里用递增数字方便测试断言 */
    private final AtomicLong callSeq = new AtomicLong();

    public MockLlmAdapter() {
        this(Duration.ofMillis(30));
    }

    public MockLlmAdapter(Duration delayPerChar) {
        this.delayPerChar = delayPerChar == null ? Duration.ZERO : delayPerChar;
    }

    @Override
    public String id() {
        return ID;
    }

    /**
     * 先按规则算出完整的回答，再翻译成片段：正文逐字吐出，最后是用量和结束。
     * 真实模型是边生成边返回的；Mock 提前算好再慢慢吐，对调用方来说看起来是一样的。
     */
    @Override
    public LlmStream stream(LlmRequest request) {
        Message last = request.lastMessage();

        if (last.role() == Role.TOOL) {
            String text = last.error()
                ? "工具执行失败了：" + last.text()
                : "工具返回的结果是：" + last.text();
            return new TypingStream(endTurn(text, request), delayPerChar);
        }

        String input = last.text();
        if (input.startsWith("/fail")) {
            // 约定：错误从 next() 抛出，而不是从 stream() 抛出（见 ILlmPort 的约定 2）
            return LlmStreams.failed(new LlmException(LlmException.Kind.SERVER, "Mock 模拟的服务端错误"));
        }
        if (asksForTime(input) && request.hasTool(TIME_TOOL)) {
            ToolCall call = new ToolCall("call_" + callSeq.incrementAndGet(), TIME_TOOL, "{}");
            return LlmStreams.of(new LlmResponse("", List.of(call), FinishReason.TOOL_USE, usage(request, ""), ID));
        }
        return new TypingStream(endTurn("（Mock）你说的是：" + input, request), delayPerChar);
    }

    private static boolean asksForTime(String input) {
        String s = input.toLowerCase(Locale.ROOT);
        return s.contains("几点") || s.contains("时间") || s.contains("time");
    }

    private static LlmResponse endTurn(String text, LlmRequest request) {
        return new LlmResponse(text, List.of(), FinishReason.END_TURN, usage(request, text), ID);
    }

    /** 粗略估算：约 4 个字符 1 个 token。Mock 只需要数量级对 */
    private static TokenUsage usage(LlmRequest request, String output) {
        long inputChars = request.system().length()
            + request.messages().stream().mapToLong(m -> m.text().length()).sum();
        return new TokenUsage(Math.max(1, inputChars / 4), output.length() / 4);
    }

    /**
     * 像打字一样，一个字符一个字符地吐出回答，然后是 Usage 和 Finish。
     *
     * <p>怎么做到“随时可以中止”：字符之间的停顿用 {@code closed.await(间隔)} 而不是 Thread.sleep。
     * 平时它等满间隔就超时返回；一旦有人调用 close()，latch 归零，等待立刻结束。
     */
    private static final class TypingStream implements LlmStream {

        /** 依次要返回的片段：每个字一个 TextDelta，最后是 Usage、Finish */
        private final List<StreamChunk> chunks = new ArrayList<>();
        private final Duration delay;
        private final CountDownLatch closed = new CountDownLatch(1);
        private int index;

        TypingStream(LlmResponse response, Duration delay) {
            this.delay = delay;
            // 按“码点”切分而不是按 char：emoji 和部分汉字由两个 char 组成，按 char 切会切出半个字符
            String text = response.text();
            for (int i = 0; i < text.length(); ) {
                int cp = text.codePointAt(i);
                chunks.add(new StreamChunk.TextDelta(new String(Character.toChars(cp))));
                i += Character.charCount(cp);
            }
            chunks.add(new StreamChunk.Usage(response.usage()));
            chunks.add(new StreamChunk.Finish(response.finishReason(), ID));
        }

        @Override
        public StreamChunk next() {
            if (index >= chunks.size()) {
                throw new IllegalStateException("流已经结束");
            }
            StreamChunk next = chunks.get(index);
            if (next instanceof StreamChunk.TextDelta) {
                pause(); // 只在“打字”时停顿
            } else if (closed.getCount() == 0) {
                throw new CancelledException();
            }
            index++;
            return next;
        }

        /** 停顿一下；停顿期间被关闭就抛 CancelledException */
        private void pause() {
            try {
                boolean wasClosed = delay.isZero()
                    ? closed.getCount() == 0
                    : closed.await(delay.toMillis(), TimeUnit.MILLISECONDS);
                if (wasClosed) {
                    throw new CancelledException();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancelledException();
            }
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }
}
