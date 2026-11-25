package com.pubbge.liharness.core.llm.mock;

import com.pubbge.liharness.core.common.CancellationToken;
import com.pubbge.liharness.core.common.CancelledException;
import com.pubbge.liharness.core.llm.ChatModel;
import com.pubbge.liharness.core.llm.ChatRequest;
import com.pubbge.liharness.core.llm.ChatResponse;
import com.pubbge.liharness.core.llm.Message;
import com.pubbge.liharness.core.llm.ModelException;
import com.pubbge.liharness.core.llm.Role;
import com.pubbge.liharness.core.llm.StopReason;
import com.pubbge.liharness.core.llm.StreamListener;
import com.pubbge.liharness.core.llm.ToolCall;
import com.pubbge.liharness.core.llm.Usage;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 不联网的假模型。按固定规则回答，行为完全可预测：
 * <ol>
 *   <li>最后一条是工具结果 → 把结果组织成最终回答（END_TURN）</li>
 *   <li>用户问时间，且请求里提供了 get_time 工具 → 请求调用 get_time（TOOL_USE）</li>
 *   <li>用户输入以 {@code /fail} 开头 → 抛 ModelException，用来演练错误处理</li>
 *   <li>其他 → 复述用户输入（END_TURN）</li>
 * </ol>
 * 文本会按字符逐个流式输出，模拟真实模型的速度。
 */
public final class MockChatModel implements ChatModel {

    public static final String ID = "mock";
    public static final String PROTOCOL = "mock";
    static final String TIME_TOOL = "get_time";

    /** 两个字符之间的间隔。界面演示用 30ms 左右看得清，测试用 Duration.ZERO */
    private final Duration delayPerChar;
    /** 用来生成工具调用 id。真实模型的 id 是随机串，这里用递增数字方便测试断言 */
    private final AtomicLong callSeq = new AtomicLong();

    public MockChatModel() {
        this(Duration.ofMillis(30));
    }

    public MockChatModel(Duration delayPerChar) {
        this.delayPerChar = delayPerChar == null ? Duration.ZERO : delayPerChar;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String protocol() {
        return PROTOCOL;
    }

    @Override
    public ChatResponse chat(ChatRequest request, StreamListener listener, CancellationToken cancel)
        throws ModelException {
        cancel.throwIfCancelled();
        Message last = request.lastMessage();

        if (last.role() == Role.TOOL) {
            String text = last.error()
                ? "工具执行失败了：" + last.text()
                : "工具返回的结果是：" + last.text();
            return stream(text, request, listener, cancel);
        }

        String input = last.text();
        if (input.startsWith("/fail")) {
            throw new ModelException(ModelException.Kind.SERVER, "Mock 模拟的服务端错误");
        }
        if (asksForTime(input) && request.hasTool(TIME_TOOL)) {
            ToolCall call = new ToolCall("call_" + callSeq.incrementAndGet(), TIME_TOOL, "{}");
            return new ChatResponse("", List.of(call), StopReason.TOOL_USE, usage(request, ""), ID);
        }
        return stream("（Mock）你说的是：" + input, request, listener, cancel);
    }

    private static boolean asksForTime(String input) {
        String s = input.toLowerCase(Locale.ROOT);
        return s.contains("几点") || s.contains("时间") || s.contains("time");
    }

    /** 按码点逐个推送文本，推完返回完整回复 */
    private ChatResponse stream(String text, ChatRequest request, StreamListener listener, CancellationToken cancel) {
        for (int i = 0; i < text.length(); ) {
            cancel.throwIfCancelled();
            int cp = text.codePointAt(i);
            listener.onTextDelta(new String(Character.toChars(cp)));
            i += Character.charCount(cp);
            pause(cancel);
        }
        cancel.throwIfCancelled(); // 最后一个字推完后才被取消，也算取消，不返回结果
        return new ChatResponse(text, List.of(), StopReason.END_TURN, usage(request, text), ID);
    }

    private void pause(CancellationToken cancel) {
        if (delayPerChar.isZero()) {
            return;
        }
        try {
            Thread.sleep(delayPerChar.toMillis());
        } catch (InterruptedException e) {
            // 线程池 shutdownNow() 会中断线程：恢复中断标志，并当作取消处理
            Thread.currentThread().interrupt();
            cancel.cancel();
            throw new CancelledException();
        }
    }

    /** 粗略估算：约 4 个字符 1 个 token。Mock 只需要数量级对 */
    private static Usage usage(ChatRequest request, String output) {
        long inputChars = request.system().length()
            + request.messages().stream().mapToLong(m -> m.text().length()).sum();
        return new Usage(Math.max(1, inputChars / 4), output.length() / 4);
    }
}
