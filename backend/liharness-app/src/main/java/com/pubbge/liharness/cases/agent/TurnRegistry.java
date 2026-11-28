package com.pubbge.liharness.cases.agent;

import com.pubbge.liharness.domain.shared.cancel.CancellationToken;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * 正在进行的回合。规则：<b>同一个会话同一时刻只能有一个回合</b>。
 *
 * <p>为什么：两个回合同时读同一份历史、各自追加消息，历史就会交错成
 * “用户 A、用户 B、助手回答 A、助手回答 B”，既不满足 Agent Loop 不变量，也没人看得懂。
 * 不同会话之间互不影响，可以并发。
 */
@Component
public class TurnRegistry {

    /** 一个正在进行的回合 */
    public record RunningTurn(String sessionId, String turnId, CancellationToken cancel) {
    }

    private final ConcurrentMap<String, RunningTurn> bySession = new ConcurrentHashMap<>();

    /**
     * 尝试在会话上开始一个回合。
     *
     * @return 会话空闲时返回新回合；已经有回合在进行时返回 empty
     */
    public Optional<RunningTurn> tryStart(String sessionId) {
        RunningTurn turn = new RunningTurn(sessionId, UUID.randomUUID().toString(), new CancellationToken());
        // putIfAbsent 是原子的：两个请求同时到达，只有一个能放进去
        return bySession.putIfAbsent(sessionId, turn) == null ? Optional.of(turn) : Optional.empty();
    }

    /** 回合结束。只移除自己：remove(key, value) 保证不会误删同一会话后来开始的回合 */
    public void finish(RunningTurn turn) {
        bySession.remove(turn.sessionId(), turn);
    }

    public Collection<RunningTurn> running() {
        return List.copyOf(bySession.values());
    }
}
