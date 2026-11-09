package com.hapbang.auxiliary.global.scheduling;

import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * advisory lock을 메모리로 흉내 내는 가짜 DB (#716, 지갑 #598 테스트를 옮김). {@link #pod()}마다 다른 session(= 다른 pod의 락 연결)을 준다.
 * PostgreSQL처럼 같은 session이 다시 잡으면 성공한다(재진입). 실제 PostgreSQL 동작은 {@code JobLockPostgresIntegrationTest}가 본다.
 */
final class FakeAdvisoryLocks {

    private final Map<Long, Object> owners = new ConcurrentHashMap<>();
    private volatile boolean failing;

    /** 새 pod의 락. 지표는 내지 않는다 */
    JobLock pod() {
        return pod(null);
    }

    /** 새 pod의 락. 건너뜀 지표를 registry에 낸다 */
    JobLock pod(MeterRegistry registry) {
        return new JobLock(session(), registry(registry));
    }

    /** true면 락 시도·해제가 SQLException으로 실패한다(DB 연결 불가 흉내) */
    void failing(boolean failing) {
        this.failing = failing;
    }

    /** 누군가 그 작업의 락을 쥐고 있는지 */
    boolean isLocked(JobLockKey job) {
        return owners.containsKey(job.lockKey());
    }

    AdvisoryLockSession session() {
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

    /** 지표 registry가 있거나 없는 ObjectProvider */
    static ObjectProvider<MeterRegistry> registry(MeterRegistry registry) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (registry != null) {
            beans.addBean("meterRegistry", registry);
        }
        return beans.getBeanProvider(MeterRegistry.class);
    }

    private void failIfNeeded() throws SQLException {
        if (failing) {
            throw new SQLException("가짜 DB 연결 불가");
        }
    }
}
