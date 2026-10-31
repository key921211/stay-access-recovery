package com.handies.accessrecovery;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FakeDoorlockClient {

    public enum Scenario {
        SUCCESS,
        TRANSIENT_THEN_SUCCESS,
        ALWAYS_FAIL,
        TIMEOUT_AFTER_ISSUE,
        ALWAYS_TIMEOUT_AFTER_ISSUE
    }

    private Scenario scenario;
    private final Map<UUID, Grant> grants = new HashMap<>();
    private final Map<UUID, Integer> calls = new HashMap<>();

    public FakeDoorlockClient(@Value("${doorlock.fake.scenario:SUCCESS}") Scenario scenario) {
        this.scenario = scenario;
    }

    public synchronized Grant issue(UUID requestId, long reservationId, String roomNumber) {
        int attempt = calls.merge(requestId, 1, Integer::sum);
        Grant existing = grants.get(requestId);
        if (existing != null) {
            if (existing.reservationId() != reservationId || !existing.roomNumber().equals(roomNumber)) {
                throw new IllegalArgumentException("같은 요청 ID를 다른 예약/객실에 사용할 수 없습니다.");
            }
            if (scenario == Scenario.ALWAYS_TIMEOUT_AFTER_ISSUE) {
                throw new DoorlockException("TIMEOUT");
            }
            return existing;
        }
        if (scenario == Scenario.ALWAYS_FAIL || (scenario == Scenario.TRANSIENT_THEN_SUCCESS && attempt == 1)) {
            throw new DoorlockException("TEMPORARY_ERROR");
        }

        Grant grant = new Grant(UUID.randomUUID().toString(), reservationId, roomNumber);
        grants.put(requestId, grant);
        if (scenario == Scenario.TIMEOUT_AFTER_ISSUE || scenario == Scenario.ALWAYS_TIMEOUT_AFTER_ISSUE) {
            throw new DoorlockException("TIMEOUT");
        }
        return grant;
    }

    // shortcut: Fake의 발급/멱등 기록은 프로세스 메모리에만 보관하며 실제 연동 시 외부 시스템이 보장해야 한다.
    synchronized void reset(Scenario scenario) {
        this.scenario = scenario;
        grants.clear();
        calls.clear();
    }

    synchronized int issuedGrantCount() {
        return grants.size();
    }

    synchronized int callCount(UUID requestId) {
        return calls.getOrDefault(requestId, 0);
    }

    public record Grant(String grantId, long reservationId, String roomNumber) {
    }

    public static class DoorlockException extends RuntimeException {
        public DoorlockException(String reason) {
            super(reason);
        }
    }
}
