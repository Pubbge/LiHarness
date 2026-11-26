package com.pubbge.liharness.domain.llm.model.valobj;

/**
 * 模型响应流里的一个片段。
 *
 * <p>一次调用的片段序列长这样：
 * <pre>
 *  ReasoningDelta("先看看…")   ← 推理模型的思考过程（可能没有）
 *  TextDelta("你") TextDelta("好")
 *  ToolCallDelta(0, "call_1", "get_time", "")      ← 第一块带 id 和名字
 *  ToolCallDelta(0, null, null, "{\"tz\":")          ← 后面的块只带参数的一小段
 *  ToolCallDelta(0, null, null, "\"UTC\"}")
 *  Usage(输入 120 / 输出 18)                          ← 可能没有
 *  Finish(TOOL_USE, "deepseek-chat")                 ← 一定是最后一个
 * </pre>
 *
 * <p><b>为什么切得这么细</b>：适配器只做“翻译”，把各家的原始数据翻译成这几种片段，不做任何拼装。
 * 拼成完整消息是 {@link com.pubbge.liharness.domain.llm.service.BlockAssembler} 的事，在领域层统一做一次。
 * 这样加一个新的模型服务，只需要写翻译，不需要再写一遍“工具调用怎么拼”“截断了怎么办”。
 * 这个划分参考了 deepseek-harness-java 的 StreamChunk。
 *
 * <p>为什么用 sealed 接口：片段的种类是有限且固定的，sealed 让编译器知道全部种类，
 * 调用方用 instanceof 判断自己关心的，不关心的直接忽略。
 */
public sealed interface StreamChunk {

    /** 回答正文的一小段 */
    record TextDelta(String text) implements StreamChunk {
    }

    /**
     * 思考过程的一小段（DeepSeek-R1 这类推理模型的 reasoning_content）。
     * 只用来显示给用户看，<b>不会</b>放进对话历史：模型服务要求下一轮请求里不要带上它。
     */
    record ReasoningDelta(String text) implements StreamChunk {
    }

    /**
     * 工具调用的一小段。同一个调用被切成很多块，靠 {@code index} 对上号。
     *
     * @param index          第几个工具调用（一次回复里可能有好几个）
     * @param id             调用 id，通常只在第一块里有，后面是 null
     * @param name           工具名（或它的一段），通常只在第一块里有
     * @param argumentsDelta 参数 JSON 的一小段，可能为空字符串
     */
    record ToolCallDelta(int index, String id, String name, String argumentsDelta) implements StreamChunk {

        public ToolCallDelta {
            argumentsDelta = argumentsDelta == null ? "" : argumentsDelta;
        }
    }

    /** token 用量。模型服务通常在流的末尾给出；没给就没有这个片段 */
    record Usage(TokenUsage usage) implements StreamChunk {
    }

    /**
     * 流结束。一定是最后一个片段，之后不能再调用 next()。
     *
     * @param reason 模型服务报告的结束原因（适配器已经翻译成我们的枚举）
     * @param model  实际响应的模型名，没有就是空字符串
     */
    record Finish(FinishReason reason, String model) implements StreamChunk {

        public Finish {
            if (reason == null) {
                throw new IllegalArgumentException("reason 不能为空");
            }
            model = model == null ? "" : model;
        }
    }
}
