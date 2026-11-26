package com.pubbge.liharness.domain.llm.adapter.port;

import com.pubbge.liharness.domain.llm.model.valobj.LlmException;
import com.pubbge.liharness.domain.llm.model.valobj.StreamChunk;
import com.pubbge.liharness.domain.shared.cancel.CancelledException;

/**
 * 一次模型调用的响应流：调用方按自己的节奏一个一个地“拉”片段。
 *
 * <pre>
 *  next() ──► TextDelta ──► TextDelta ──► … ──► Usage ──► Finish
 *                                                         之后不能再调用 next()
 * </pre>
 *
 * <p><b>为什么是“拉”</b>（调用方调用 next()），而不是“推”（像 deepseek-harness 那样返回 Flow.Publisher，
 * 由模型在自己的线程上回调订阅者）：
 * <ul>
 *   <li><b>线程清楚</b>：片段总是在调用 next() 的线程上处理，Agent Loop 就是一个普通的 while 循环；</li>
 *   <li><b>错误就是异常</b>：next() 直接抛 {@link LlmException}，调用方用普通的 try/catch，
 *       不需要在 onError 回调里先存起来、再回到主线程抛出；</li>
 *   <li><b>停止立即生效</b>：任何线程调用 {@link #close()}，阻塞中的 next() 立刻醒来并抛 CancelledException。
 *       推送式的订阅者只能等下一个片段到来时检查标志，或者定时轮询。</li>
 * </ul>
 * Anthropic 官方 Java SDK（StreamResponse：可迭代 + close）和 Claude Agent SDK（异步迭代器 + interrupt）
 * 也是这个形状。
 *
 * <p>线程安全：{@link #next()} 只能由一个线程调用；{@link #close()} 可以由任何线程、任何时刻调用，可以重复调用。
 */
public interface LlmStream extends AutoCloseable {

    /**
     * 阻塞直到下一个片段。最后一个片段一定是 {@link StreamChunk.Finish}。
     *
     * @throws LlmException          模型调用失败（连不上、Key 错误、限流、读到一半断了……）
     * @throws CancelledException    流已经被 {@link #close()} 关闭
     * @throws IllegalStateException 已经返回过 Finish，还继续调用
     */
    StreamChunk next() throws LlmException;

    /**
     * 中止并释放资源（网络连接等）。可以在任何线程、任何时刻调用；重复调用没有副作用。
     * 正常读完以后也要调用（try-with-resources 会自动调用）。
     */
    @Override
    void close();
}
