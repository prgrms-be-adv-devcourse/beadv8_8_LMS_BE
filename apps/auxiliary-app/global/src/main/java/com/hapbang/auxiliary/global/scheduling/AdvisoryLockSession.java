package com.hapbang.auxiliary.global.scheduling;

import java.sql.SQLException;

/**
 * advisory lock을 잡고 푸는 DB session 하나 (지갑 #598 구조, #715). {@link JobLock}이 직렬화해서 부르므로 구현은 스레드 안전하지 않아도 된다.
 * 운영 구현은 {@link PostgresAdvisoryLockSession}이고, 테스트는 메모리 구현으로 바꿔 끼운다.
 */
public interface AdvisoryLockSession extends AutoCloseable {

    /** 번호의 락을 기다리지 않고 잡는다. 다른 session이 쥐고 있으면 false */
    boolean tryLock(long key) throws SQLException;

    /** 이 session이 쥔 락을 푼다. 쥐고 있지 않았으면(연결이 끊겨 이미 풀렸으면) false */
    boolean unlock(long key) throws SQLException;

    @Override
    void close();
}
