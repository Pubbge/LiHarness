package com.pubbge.liharness.domain.session.adapter.repository;

import com.pubbge.liharness.domain.llm.message.Message;
import java.util.List;

/**
 * 会话历史的存储。
 *
 * <p>第 06 章只需要“读历史、追加消息”两个操作，用内存实现。第 11 章会扩展这个接口（创建、列表、删除），
 * 并提供基于 JSONL 文件的实现。Agent Loop 不依赖它：回合需要的历史由调用方读出来放进 TurnRequest。
 */
public interface ISessionRepository {

    /** 读出会话的全部消息；会话不存在时返回空列表 */
    List<Message> history(String sessionId);

    /** 追加一批消息（一个回合的 newMessages）。要么全部成功，要么抛异常 */
    void append(String sessionId, List<Message> messages);
}
