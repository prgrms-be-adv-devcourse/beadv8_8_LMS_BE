package com.hapbang.billing.global.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.jdbc.core.JdbcTemplate;

import com.hapbang.testsupport.IntegrationTest;

// 실행 락(#598, BD-138)을 실제 PostgreSQL advisory lock으로 검증한다. 락 전용 연결을 하나씩 가진 JobLock 두 개가 두 pod다.
@IntegrationTest
class JobLockPostgresIntegrationTest {

    @Autowired
    private JdbcConnectionDetails connectionDetails;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 이 테스트에서 연 락 연결 (pid 확인·강제 종료용)
    private final List<Connection> opened = new CopyOnWriteArrayList<>();
    private final List<JobLock> pods = new ArrayList<>();

    @AfterEach
    void closePods() {
        pods.forEach(JobLock::close);
    }

    @Test
    @DisplayName("운영 연결 방식(앱 datasource 접속 정보)으로 락을 잡고 풀며, 다른 연결은 그동안 잡지 못한다")
    void productionSession() {
        JobLock podA = track(new JobLock(PostgresAdvisoryLockSession.of(connectionDetails)));
        JobLock podB = track(new JobLock(PostgresAdvisoryLockSession.of(connectionDetails)));

        JobLock.Lease lease = podA.tryAcquire(BillingJob.CHARGE_RECOVERY).orElseThrow();
        assertThat(podB.tryAcquire(BillingJob.CHARGE_RECOVERY)).isEmpty();
        // 락 전용 연결(application_name)이 쥐고 있다
        assertThat(lockHolders(BillingJob.CHARGE_RECOVERY)).containsExactly("billing-app-job-lock");

        lease.close();
        assertThat(lockHolders(BillingJob.CHARGE_RECOVERY)).isEmpty();
        assertThat(podB.tryAcquire(BillingJob.CHARGE_RECOVERY)).isPresent();
    }

    @Test
    @DisplayName("PostgreSQL은 같은 session의 재획득을 허용하지만, 같은 pod의 두 번째 시도는 메모리 확인으로 막힌다")
    void reentryBlockedInMemory() throws Exception {
        JobLock pod = pod();

        JobLock.Lease lease = pod.tryAcquire(BillingJob.MEMBER_BACKFILL).orElseThrow();
        // 전제 확인: 같은 연결로 DB에 직접 물으면 다시 잡힌다(재진입)
        assertThat(query(opened.getFirst(), "select pg_try_advisory_lock(" + BillingJob.MEMBER_BACKFILL.lockKey() + ")"))
                .isTrue();
        query(opened.getFirst(), "select pg_advisory_unlock(" + BillingJob.MEMBER_BACKFILL.lockKey() + ")");

        assertThat(pod.tryAcquire(BillingJob.MEMBER_BACKFILL)).isEmpty();
        lease.close();
        assertThat(pod.tryAcquire(BillingJob.MEMBER_BACKFILL)).isPresent();
    }

    @Test
    @DisplayName("락 연결이 끊기면 락이 풀려 다른 pod가 잡고, 끊긴 pod는 예외 없이 끝낸 뒤 다음 시도에서 다시 연결한다")
    void connectionLost() throws Exception {
        JobLock podA = pod();
        JobLock podB = pod();

        JobLock.Lease lease = podA.tryAcquire(BillingJob.CHARGE_RECOVERY).orElseThrow();
        podB.tryAcquire(BillingJob.REFUND_RECOVERY).orElseThrow().close();
        terminate(opened.getFirst());

        // 끊긴 연결의 락은 PostgreSQL이 풀었다
        JobLock.Lease taken = podB.tryAcquire(BillingJob.CHARGE_RECOVERY).orElseThrow();
        // 돌던 작업이 끝나 풀어도 예외가 없다(이미 풀린 락)
        lease.close();
        assertThat(podA.tryAcquire(BillingJob.CHARGE_RECOVERY)).isEmpty();

        // 다음 시도에서 새로 연결한다
        assertThat(podA.tryAcquire(BillingJob.EXCHANGE_SETTLEMENT)).isPresent();
        assertThat(opened).hasSize(3);
        taken.close();
    }

    @Test
    @DisplayName("pod가 내려가 락 연결을 닫으면 쥐고 있던 락이 풀린다")
    void podShutdownReleases() {
        JobLock podA = pod();
        JobLock podB = pod();

        podA.tryAcquire(BillingJob.EVENT_REPUBLISH).orElseThrow();
        podA.close();

        assertThat(podB.tryAcquire(BillingJob.EVENT_REPUBLISH)).isPresent();
    }

    @Test
    @DisplayName("락 연결을 열지 못하면 JobLockException이고, 배치는 실행하지 않는다")
    void openFails() {
        JobLock pod = track(new JobLock(new PostgresAdvisoryLockSession(() -> {
            throw new SQLException("연결 불가");
        })));
        AtomicInteger runs = new AtomicInteger();

        pod.runOrSkip(BillingJob.CHARGE_RECOVERY, runs::incrementAndGet);

        assertThat(runs).hasValue(0);
    }

    @Test
    @DisplayName("두 pod가 같은 순간 같은 배치를 시작하면 한쪽만 실행한다")
    void concurrentStart() throws Exception {
        JobLock podA = pod();
        JobLock podB = pod();
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        Runnable task = () -> {
            runs.incrementAndGet();
            sleep(500);
        };

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<?>> futures = new ArrayList<>();
            for (JobLock pod : List.of(podA, podB)) {
                futures.add(executor.submit(() -> {
                    start.await();
                    pod.runOrSkip(BillingJob.EXCHANGE_SETTLEMENT, task);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        }

        assertThat(runs).hasValue(1);
    }

    private JobLock pod() {
        return track(new JobLock(new PostgresAdvisoryLockSession(() -> {
            Connection connection = DriverManager.getConnection(connectionDetails.getJdbcUrl(),
                    connectionDetails.getUsername(), connectionDetails.getPassword());
            opened.add(connection);
            return connection;
        })));
    }

    private JobLock track(JobLock pod) {
        pods.add(pod);
        return pod;
    }

    // bigint 키의 advisory lock은 pg_locks에서 classid = 상위 32비트, objid = 하위 32비트, objsubid = 1이다
    private List<String> lockHolders(BillingJob job) {
        return jdbcTemplate.queryForList("select a.application_name from pg_locks l join pg_stat_activity a on a.pid = l.pid "
                + "where l.locktype = 'advisory' and l.granted and l.classid::bigint = 0 and l.objid::bigint = ? and l.objsubid = 1",
                String.class, job.lockKey());
    }

    private void terminate(Connection connection) throws SQLException {
        int pid;
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("select pg_backend_pid()")) {
            resultSet.next();
            pid = resultSet.getInt(1);
        }
        jdbcTemplate.queryForObject("select pg_terminate_backend(?)", Boolean.class, pid);
    }

    private static boolean query(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getBoolean(1);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
