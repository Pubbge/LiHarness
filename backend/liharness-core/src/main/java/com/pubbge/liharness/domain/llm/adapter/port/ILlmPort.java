package com.pubbge.liharness.domain.llm.adapter.port;

import com.pubbge.liharness.domain.llm.model.valobj.LlmException;
import com.pubbge.liharness.domain.llm.model.valobj.LlmRequest;
import com.pubbge.liharness.domain.llm.service.LlmGenerationService;

/**
 * 模型端口：领域层对“模型服务”的全部要求。Mock、OpenAI 兼容接口、以后的其他协议都在
 * infrastructure.adapter.llm 下实现这一个接口。
 *
 * <p>只有一个生成入口：{@link #stream}。参数里只有聊天本身的内容（{@link LlmRequest}），
 * 没有回调、没有取消令牌：
 * <ul>
 *   <li>流式输出是<b>返回值</b>（{@link LlmStream}），谁要显示谁去读；</li>
 *   <li>要中止就<b>关闭这个流</b>，和关闭一个文件、一个网络连接是同一个动作；</li>
 *   <li>要完整回复，用领域服务 {@link LlmGenerationService} 在流上拼，适配器不用管。</li>
 * </ul>
 * deepseek-harness-java 的 LlmAdapter 也是只有 stream 一个生成入口、完整消息由领域服务拼装。
 *
 * <p>实现方必须遵守的约定：
 * <ol>
 *   <li><b>无状态</b>：不记对话历史。历史全部在 request.messages() 里，每次完整传入。
 *       所以同一个实例可以被多个回合同时使用。</li>
 *   <li><b>{@link #stream} 立即返回</b>：只负责发出请求，不等待响应。所有错误（连不上、Key 错误、限流……）
 *       都从 {@link LlmStream#next()} 抛出。这样调用方拿到流以后，任何时刻都可以关闭它，
 *       包括“还在等服务器响应”的时候。</li>
 *   <li><b>只翻译、不拼装</b>：把原始数据翻译成 {@code StreamChunk}，最后一个片段必须是 Finish。</li>
 *   <li><b>错误分类</b>：失败时抛 {@link LlmException} 并给出正确的 {@link LlmException.Kind}。</li>
 *   <li><b>不重试</b>：重试由外面的装饰器负责（第 07 章的 RetryingLlmPort），实现方只管调一次。</li>
 * </ol>
 */
public interface ILlmPort {

    /** 模型标识，显示在界面上、记在日志里，例如 "mock"、"deepseek-chat" */
    String id();

    /**
     * 发出请求，返回一个可以一个一个读取片段的响应流。调用方用完必须关闭它（推荐 try-with-resources）。
     */
    LlmStream stream(LlmRequest request);
}
