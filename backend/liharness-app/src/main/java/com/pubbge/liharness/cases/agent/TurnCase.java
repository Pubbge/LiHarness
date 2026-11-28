package com.pubbge.liharness.cases.agent;

import com.pubbge.liharness.cases.settings.ModelCase;
import com.pubbge.liharness.domain.agent.contract.outbound.ITurnEventListener;
import com.pubbge.liharness.domain.agent.model.valobj.TurnOutcome;
import com.pubbge.liharness.domain.agent.model.valobj.TurnRequest;
import com.pubbge.liharness.domain.agent.model.valobj.TurnResult;
import com.pubbge.liharness.domain.agent.service.run.AgentLoop;
import com.pubbge.liharness.domain.llm.error.LlmException;
import com.pubbge.liharness.domain.session.adapter.repository.ISessionRepository;
import com.pubbge.liharness.domain.tool.model.valobj.ToolSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 跑一个回合的完整流程：读历史 → 组装请求 → Agent Loop → 保存新消息。
 *
 * <p>它是一个“用例”（cases 层）：把领域对象按业务流程编排起来。Controller（trigger 层）只管 HTTP 和 SSE，
 * 领域对象（AgentLoop、ISessionRepository）只管各自的规则，流程写在这里。后面几章的功能（设置里选模型、
 * 系统提示词组装、上下文压缩、审批）都加在这里，Controller 基本不用再动。
 */
@Service
public class TurnCase {

    private static final Logger log = LoggerFactory.getLogger(TurnCase.class);

    /** 第 12 章换成按段落组装的系统提示词 */
    static final String SYSTEM_PROMPT = """
        你是 LiHarness，一个运行在用户电脑上的 AI 助手。
        需要实时信息或需要操作时，调用提供给你的工具，不要编造结果。
        回答使用用户的语言，简洁直接。""";

    private final AgentLoop agentLoop;
    private final ModelCase modelCase;
    private final ToolSet builtinTools;
    private final ISessionRepository sessionRepository;

    public TurnCase(AgentLoop agentLoop, ModelCase modelCase, ToolSet builtinTools, ISessionRepository sessionRepository) {
        this.agentLoop = agentLoop;
        this.modelCase = modelCase;
        this.builtinTools = builtinTools;
        this.sessionRepository = sessionRepository;
    }

    /**
     * 回合开始前取模型。在开 SSE 之前调用：模型没配好直接返回 400，而不是开了流再推一个错误。
     *
     * @throws LlmException {@link LlmException.Kind#NOT_CONFIGURED}，消息可以直接展示给用户
     */
    public ModelCase.ActiveModel checkModel() throws LlmException {
        return modelCase.current();
    }

    /** 回合开始时用的是哪个模型，整个回合就用哪个：回合进行中改了设置，不会前一步 A 后一步 B */
    public TurnResult run(TurnRegistry.RunningTurn turn, ModelCase.ActiveModel active, String input,
            ITurnEventListener listener) {
        long start = System.currentTimeMillis();
        TurnRequest request = new TurnRequest(
            turn.sessionId(), turn.turnId(), active.model(), SYSTEM_PROMPT,
            sessionRepository.history(turn.sessionId()), input, builtinTools, null, active.profile().maxOutputTokens());

        TurnResult result = agentLoop.run(request, listener, turn.cancel());

        // 模型一个字都没回就失败了（比如 API Key 错误）：不保存，免得用户重试时历史里堆满重复的问题
        boolean nothingHappened = result.outcome() == TurnOutcome.FAILED && result.newMessages().size() == 1;
        if (!nothingHappened) {
            sessionRepository.append(turn.sessionId(), result.newMessages());
        }
        log.info("回合结束 session={} turn={} outcome={} steps={} usage={} 耗时={}ms",
            turn.sessionId(), turn.turnId(), result.outcome(), result.steps(), result.usage(),
            System.currentTimeMillis() - start);
        return result;
    }
}
