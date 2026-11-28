package com.pubbge.liharness.infrastructure.adapter.repository;

import com.pubbge.liharness.domain.llm.message.Message;
import com.pubbge.liharness.domain.session.adapter.repository.ISessionRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 内存里的会话存储。重启就丢，第 11 章换成文件存储 */
public final class InMemorySessionRepository implements ISessionRepository {

    private final Map<String, List<Message>> sessions = new ConcurrentHashMap<>();

    @Override
    public List<Message> history(String sessionId) {
        List<Message> list = sessions.get(sessionId);
        if (list == null) {
            return List.of();
        }
        synchronized (list) {
            return List.copyOf(list);
        }
    }

    @Override
    public void append(String sessionId, List<Message> messages) {
        List<Message> list = sessions.computeIfAbsent(sessionId, id -> new ArrayList<>());
        synchronized (list) {
            list.addAll(messages);
        }
    }
}
