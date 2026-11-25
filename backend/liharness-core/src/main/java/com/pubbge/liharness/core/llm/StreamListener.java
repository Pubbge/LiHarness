package com.pubbge.liharness.core.llm;

/**
 * 流式输出回调。模型每生成一小段文本，就回调一次。
 *
 * <p>约定：
 * <ul>
 *   <li>在调用 {@link ChatModel#chat} 的同一个线程上回调，实现方不需要考虑并发。</li>
 *   <li>所有 delta 拼起来 == 最终 {@link ChatResponse#text()}。</li>
 *   <li>回调里不要做耗时操作，它会拖慢读取模型响应的速度。</li>
 * </ul>
 */
@FunctionalInterface
public interface StreamListener {

    void onTextDelta(String delta);

    /** 不关心流式输出时用 */
    StreamListener NONE = delta -> { };
}
