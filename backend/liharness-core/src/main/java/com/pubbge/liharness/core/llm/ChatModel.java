package com.pubbge.liharness.core.llm;

import com.pubbge.liharness.core.common.CancellationToken;
import com.pubbge.liharness.core.common.CancelledException;

/**
 * 模型服务：Agent 的“大脑”。Mock、OpenAI 兼容接口、以后的其他协议都实现这一个接口。
 *
 * <p>实现方必须遵守的约定：
 * <ol>
 *   <li><b>阻塞</b>：拿到完整回复才返回。流式文本通过 listener 边生成边回调。</li>
 *   <li><b>无状态</b>：不记对话历史。历史全部在 request.messages() 里，每次完整传入。
 *       所以同一个实例可以被多个回合并发使用。</li>
 *   <li><b>可取消</b>：cancel 被触发后尽快抛 {@link CancelledException}，不返回半截结果。</li>
 *   <li><b>错误分类</b>：失败时抛 {@link ModelException} 并给出正确的 {@link ModelException.Kind}。</li>
 *   <li><b>不重试</b>：重试策略由调用方决定，实现方只管调一次。</li>
 * </ol>
 */
public interface ChatModel {

    /** 模型标识，显示在界面上、记在日志里，例如 "mock"、"gpt-4o-mini" */
    String id();

    /** 采用的协议 openAi Anthropic等等 */
    String protocol();

    /**
     * 调用一次模型。
     *
     * @param request  输入
     * @param listener 流式文本回调，不需要时传 {@link StreamListener#NONE}
     * @param cancel   取消令牌
     * @return 完整回复
     * @throws ModelException     调用失败
     * @throws CancelledException 被取消
     */
    ChatResponse chat(ChatRequest request, StreamListener listener, CancellationToken cancel) throws ModelException;
}
