package com.hapbang.auxiliary.global.scheduling;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;

import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;

import lombok.extern.slf4j.Slf4j;

/**
 * 락 전용 DB 연결 하나로 PostgreSQL session advisory lock을 잡고 푼다 (지갑 #598 구조를 가져옴, #715).
 * <ul>
 *     <li>연결은 Hikari pool 밖에 두고 첫 락 시도 때 맺는다. 배치를 쓰지 않는 실행에서는 연결하지 않는다.</li>
 *     <li>session 단위 락이다. 배치가 건마다 트랜잭션을 여러 번 열기 때문에 트랜잭션 단위 락은 첫 트랜잭션에서 풀린다.</li>
 *     <li>autocommit이라 이 연결은 트랜잭션을 열어 둔 채 쉬지 않는다.</li>
 *     <li>락을 잡기 전에 연결을 확인하고, 끊겼으면 새로 맺는다. 끊긴 연결의 락은 PostgreSQL이 이미 풀었으므로 warn 로그를 남긴다.</li>
 * </ul>
 */
@Slf4j
public class PostgresAdvisoryLockSession implements AdvisoryLockSession {

    // JobLock이 모든 호출을 한 모니터로 직렬화하므로, 여기서 기다리는 시간이 곧 다른 배치·Backfill의 대기 시간이다(#598 리뷰).
    // pg_try_advisory_lock·pg_advisory_unlock은 기다리지 않고 바로 답하므로 상한을 짧게 둔다.
    // 호출당 최악: 락 시도 = 확인 1 + 연결 3 + 쿼리 3 = 약 7초, 해제 = 확인 1 + 쿼리 3 = 약 4초.
    /** 연결 확인(isValid)을 기다리는 최대 초 */
    static final int VALID_TIMEOUT_SECONDS = 1;
    /** 연결을 맺을 때 기다리는 최대 초 */
    static final int CONNECT_TIMEOUT_SECONDS = 3;
    /** 쿼리 응답을 기다리는 최대 초 */
    static final int SOCKET_TIMEOUT_SECONDS = 3;

    private final Opener opener;
    private Connection connection;

    /** 연결을 여는 방법. 테스트가 연결 실패를 흉내 낼 때 바꾼다 */
    @FunctionalInterface
    public interface Opener {
        Connection open() throws SQLException;
    }

    public PostgresAdvisoryLockSession(Opener opener) {
        this.opener = opener;
    }

    /** 앱 datasource와 같은 접속 정보로 연다 */
    public static PostgresAdvisoryLockSession of(JdbcConnectionDetails details) {
        return new PostgresAdvisoryLockSession(() -> {
            Properties properties = new Properties();
            if (details.getUsername() != null) {
                properties.setProperty("user", details.getUsername());
            }
            if (details.getPassword() != null) {
                properties.setProperty("password", details.getPassword());
            }
            // pg_stat_activity에서 락 연결을 알아볼 수 있게 한다. 시간 상한은 위 상수(초)다
            properties.setProperty("ApplicationName", "auxiliary-app-job-lock");
            properties.setProperty("connectTimeout", String.valueOf(CONNECT_TIMEOUT_SECONDS));
            properties.setProperty("socketTimeout", String.valueOf(SOCKET_TIMEOUT_SECONDS));
            return DriverManager.getConnection(details.getJdbcUrl(), properties);
        });
    }

    @Override
    public boolean tryLock(long key) throws SQLException {
        return call(connection(), "select pg_try_advisory_lock(?)", key);
    }

    @Override
    public boolean unlock(long key) throws SQLException {
        if (connection == null || !connection.isValid(VALID_TIMEOUT_SECONDS)) {
            // 연결이 없거나 끊겼으면 그 연결의 락은 이미 풀렸다. 다음 tryLock이 새로 연결한다
            return false;
        }
        return call(connection, "select pg_advisory_unlock(?)", key);
    }

    @Override
    public void close() {
        closeQuietly();
    }

    private Connection connection() throws SQLException {
        if (connection != null) {
            if (connection.isValid(VALID_TIMEOUT_SECONDS)) {
                return connection;
            }
            log.warn("배치 락 연결이 끊겨 다시 연결한다. 이전 연결이 쥐고 있던 락은 모두 풀렸다");
            closeQuietly();
        }
        Connection opened = opener.open();
        opened.setAutoCommit(true);
        connection = opened;
        return opened;
    }

    private static boolean call(Connection connection, String sql, long key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, key);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getBoolean(1);
            }
        }
    }

    private void closeQuietly() {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException e) {
            log.debug("배치 락 연결을 닫지 못했다", e);
        } finally {
            connection = null;
        }
    }
}
