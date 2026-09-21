package com.storex.order.repository;

import com.storex.order.model.SagaCompensationLog;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Repository
public class SagaCompensationLogRepository {

    private final Map<String, SagaCompensationLog> logDatabase = new ConcurrentHashMap<>();

    public SagaCompensationLog save(SagaCompensationLog log) {
        logDatabase.put(log.getId(), log);
        return log;
    }

    public Optional<SagaCompensationLog> findById(String id) {
        return Optional.ofNullable(logDatabase.get(id));
    }

    public List<SagaCompensationLog> findPendingLogs() {
        return logDatabase.values().stream()
                .filter(l -> "PENDING_RETRY".equals(l.getStatus()))
                .collect(Collectors.toList());
    }

    public List<SagaCompensationLog> findAll() {
        return new ArrayList<>(logDatabase.values());
    }

    public void clear() {
        logDatabase.clear();
    }
}
