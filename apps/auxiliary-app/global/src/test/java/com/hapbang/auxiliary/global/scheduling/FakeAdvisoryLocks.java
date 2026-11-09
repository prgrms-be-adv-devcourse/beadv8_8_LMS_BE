package com.hapbang.auxiliary.global.scheduling;

import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * advisory lock을 메모리로 흉내 내는 가짜 DB (지갑 #598 테스트를 가져옴, #715). {@link #pod()}마다 다른 session(= 다른 pod의 락 연결)을 준다.
 * PostgreSQL처럼 같은 session이 다시 잡으면 성공한다(재진입). 실제 PostgreSQL 동작은 {@code JobLockPostgresIntegrationTest}가 본다.
 */
public final class FakeAdvisoryLocks {

    private final Map<Long, Object> owners = new ConcurrentHashMap<>();
    private volatile boolean failing;
    private final io.micrometer.core.instrument.MeterRegistry registry;

    public FakeAdvisoryLocks() {
        this(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    /** 이 가짜 DB의 pod들이 지표를 이 registry에 낸다 */
    public FakeAdvisoryLocks(io.micrometer.core.instrument.MeterRegistry registry) {
        this.registry = registry;
    }

    /** 새 pod의 락. 이 가짜 DB를 다른 pod와 함께 쓴다 */
    public JobLock pod() {
        return new JobLock(session(), registry);
    }

    /** true면 락 시도·해제가 SQLException으로 실패한다(DB 연결 불가 흉내) */
    public void failing(boolean failing) {
        this.failing = failing;
    }

    /** 누군가 그 작업의 락을 쥐고 있는지 */
    public boolean isLocked(TestJob job) {
        return owners.containsKey(job.lockKey());
    }

    public AdvisoryLockSession session() {
        Object self = new Object();
        return new AdvisoryLockSession() {

            @Override
            public boolean tryLock(long key) throws SQLException {
                failIfNeeded();
                Object owner = owners.putIfAbsent(key, self);
                return owner == null || owner == self;
            }

            @Override
            public boolean unlock(long key) throws SQLException {
                failIfNeeded();
                return owners.remove(key, self);
            }

            @Override
            public void close() {
                owners.values().removeIf(owner -> owner == self);
            }
        };
    }

    private void failIfNeeded() throws SQLException {
        if (failing) {
            throw new SQLException("가짜 DB 연결 불가");
        }
    }
}
