package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.EnableScenarios;
import org.springframework.modulith.test.Scenario;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.in.UserJoinedEventListener;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.shared.member.event.UserJoinedEvent;
import com.hapbang.testsupport.IntegrationTest;

// 가입 이벤트·지갑 조회·동시 요청에서 회원당 지갑이 하나만 생기는지 실제 DB로 검증한다.
@IntegrationTest
@EnableScenarios
class WalletCreationIntegrationTest {

    private static final int THREADS = 8;

    @Autowired
    WalletFacade walletFacade;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PlatformTransactionManager transactionManager;

    // 빈은 @Async 프록시라 호출해도 바로 반환된다. 리스너 로직을 동기로 검증할 때는 프록시 없는 인스턴스를 쓴다.
    UserJoinedEventListener userJoinedEventListener;

    // 프록시 없는 리스너 인스턴스를 만든다.
    @BeforeEach
    void setUp() {
        userJoinedEventListener = new UserJoinedEventListener(walletFacade);
    }

    @Test
    @DisplayName("가입 이벤트가 커밋 후 전달되면 네 가지 잔액이 0인 지갑을 만든다")
    void userJoined_createsWallet(Scenario scenario) {
        scenario.publish(joined(1L, Role.USER))
                .andWaitForStateChange(() -> walletRepository.findByUserId(1L), Optional::isPresent)
                .andVerify(wallet -> assertThat(wallet).hasValueSatisfying(this::assertEmptyBalances));

        assertThat(walletCount(1L)).isEqualTo(1);
    }

    @Test
    @DisplayName("인플루언서·인증된 인플루언서 가입 이벤트에도 지갑을 만든다")
    void userJoined_influencers() {
        userJoinedEventListener.on(joined(1L, Role.INFLUENCER));
        userJoinedEventListener.on(joined(2L, Role.VERIFIED_INFLUENCER));

        assertThat(walletRepository.findByUserId(1L)).isPresent();
        assertThat(walletRepository.findByUserId(2L)).isPresent();
    }

    @Test
    @DisplayName("관리자 가입 이벤트는 처리를 마쳐도 지갑을 만들지 않는다")
    void userJoined_admin(Scenario scenario) {
        publishAndWaitForCompletion(scenario, joined(1L, Role.ADMIN));

        assertThat(walletRepository.findByUserId(1L)).isEmpty();
    }

    @Test
    @DisplayName("같은 가입 이벤트가 다시 전달되어 처리되어도 지갑은 하나이고 같은 지갑을 유지한다")
    void userJoined_redelivered(Scenario scenario) {
        UserJoinedEvent event = joined(1L, Role.USER);
        publishAndWaitForCompletion(scenario, event);
        Long walletId = walletRepository.findByUserId(1L).orElseThrow().getId();

        publishAndWaitForCompletion(scenario, event);

        assertThat(walletCount(1L)).isEqualTo(1);
        assertThat(walletRepository.findByUserId(1L).orElseThrow().getId()).isEqualTo(walletId);
    }

    @Test
    @DisplayName("같은 가입 이벤트를 다시 받아도 지갑은 하나이고 같은 지갑을 유지한다")
    void userJoined_duplicateEvent() {
        UserJoinedEvent event = joined(1L, Role.USER);
        userJoinedEventListener.on(event);
        Long walletId = walletRepository.findByUserId(1L).orElseThrow().getId();

        userJoinedEventListener.on(event);

        assertThat(walletCount(1L)).isEqualTo(1);
        assertThat(walletRepository.findByUserId(1L).orElseThrow().getId()).isEqualTo(walletId);
    }

    @Test
    @DisplayName("조회가 먼저 지갑을 만든 뒤 가입 이벤트가 처리되어도 같은 지갑 하나만 남는다")
    void walletLookupThenUserJoined() {
        Wallet created = walletFacade.getOrCreateWallet(1L);

        userJoinedEventListener.on(joined(1L, Role.USER));

        assertThat(walletCount(1L)).isEqualTo(1);
        assertThat(walletRepository.findByUserId(1L).orElseThrow().getId()).isEqualTo(created.getId());
    }

    @Test
    @DisplayName("가입 이벤트 처리 뒤 조회하면 이벤트가 만든 지갑을 그대로 반환한다")
    void userJoinedThenWalletLookup() {
        userJoinedEventListener.on(joined(1L, Role.USER));
        Long walletId = walletRepository.findByUserId(1L).orElseThrow().getId();

        assertThat(walletFacade.getOrCreateWallet(1L).getId()).isEqualTo(walletId);
        assertThat(walletCount(1L)).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 회원의 지갑 생성 요청이 동시에 들어와도 지갑은 하나이고 모두 같은 지갑을 받는다")
    void concurrentCreation() throws Exception {
        List<Callable<Long>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(() -> walletFacade.getOrCreateWallet(1L).getId());
        }

        List<Long> walletIds = runTogether(tasks);

        assertThat(walletIds).hasSize(THREADS).containsOnly(walletIds.getFirst());
        assertThat(walletCount(1L)).isEqualTo(1);
    }

    @Test
    @DisplayName("가입 이벤트 재처리와 지갑 조회가 동시에 실행되어도 지갑은 하나다")
    void concurrentEventAndLookup() throws Exception {
        UserJoinedEvent event = joined(1L, Role.USER);
        List<Callable<Long>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            if (i % 2 == 0) {
                tasks.add(() -> {
                    userJoinedEventListener.on(event);
                    return null;
                });
            } else {
                tasks.add(() -> walletFacade.getOrCreateWallet(1L).getId());
            }
        }

        List<Long> walletIds = runTogether(tasks).stream().filter(id -> id != null).toList();

        Long walletId = walletRepository.findByUserId(1L).orElseThrow().getId();
        assertThat(walletIds).containsOnly(walletId);
        assertThat(walletCount(1L)).isEqualTo(1);
    }

    @Test
    @DisplayName("생성 중 다른 트랜잭션이 먼저 지갑을 커밋하면 UNIQUE 충돌 후 그 지갑을 조회해 반환한다")
    void uniqueConflict_returnsCommittedWallet() throws Exception {
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // 다른 트랜잭션이 지갑을 INSERT하고 커밋하지 않은 채 기다린다.
            Future<Long> competitor = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                jdbcTemplate.update("INSERT INTO billing.wallet (user_id, created_at, updated_at) VALUES (?, now(), now())", 1L);
                inserted.countDown();
                await(commit);
                return jdbcTemplate.queryForObject("SELECT id FROM billing.wallet WHERE user_id = ?", Long.class, 1L);
            }));
            assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();

            // 커밋 전이라 지갑이 보이지 않으므로 INSERT하고, UNIQUE 인덱스에서 상대 트랜잭션을 기다린다.
            Future<Long> creation = executor.submit(() -> walletFacade.getOrCreateWallet(1L).getId());
            waitUntilLockWaiting();
            commit.countDown();

            assertThat(creation.get(10, TimeUnit.SECONDS)).isEqualTo(competitor.get(10, TimeUnit.SECONDS));
            assertThat(walletCount(1L)).isEqualTo(1);
        } finally {
            commit.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("호출한 쪽 트랜잭션 안에서 UNIQUE 충돌이 나도 그 트랜잭션은 오류 상태가 되지 않고 계속 쓸 수 있다")
    void uniqueConflict_insideCallerTransaction() throws Exception {
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Long> competitor = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                jdbcTemplate.update("INSERT INTO billing.wallet (user_id, created_at, updated_at) VALUES (?, now(), now())", 1L);
                inserted.countDown();
                await(commit);
                return jdbcTemplate.queryForObject("SELECT id FROM billing.wallet WHERE user_id = ?", Long.class, 1L);
            }));
            assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();

            // 호출한 쪽 트랜잭션에서 생성하고, 충돌 뒤에도 같은 트랜잭션으로 SQL을 실행해 커밋까지 한다.
            Future<Long> caller = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                Long walletId = walletFacade.getOrCreateWallet(1L).getId();
                Long sameTransactionRead = jdbcTemplate.queryForObject(
                        "SELECT id FROM billing.wallet WHERE user_id = ?", Long.class, 1L);
                assertThat(sameTransactionRead).isEqualTo(walletId);
                return walletId;
            }));
            waitUntilLockWaiting();
            commit.countDown();

            assertThat(caller.get(10, TimeUnit.SECONDS)).isEqualTo(competitor.get(10, TimeUnit.SECONDS));
            assertThat(walletCount(1L)).isEqualTo(1);
        } finally {
            commit.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("회원마다 다른 지갑을 가진다")
    void walletsAreSeparatedByUser() {
        Wallet first = walletFacade.getOrCreateWallet(1L);
        Wallet second = walletFacade.getOrCreateWallet(2L);

        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(first.getUserId()).isEqualTo(1L);
        assertThat(second.getUserId()).isEqualTo(2L);
        assertThat(walletFacade.getOrCreateWallet(1L).getId()).isEqualTo(first.getId());
    }

    // 이벤트를 커밋 후 발행하고 리스너 처리가 끝나 발행 기록이 지워질 때까지 기다린다 (테스트는 completion-mode=delete).
    private void publishAndWaitForCompletion(Scenario scenario, UserJoinedEvent event) {
        scenario.publish(event)
                .andWaitForStateChange(this::outstandingPublications, count -> count == 0)
                .andVerify(count -> assertThat(count).isZero());
    }

    // 아직 처리되지 않은 이벤트 발행 기록 수를 센다.
    private int outstandingPublications() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM event_publication", Integer.class);
    }

    // 회원 ID·역할로 가입 이벤트를 만든다.
    private UserJoinedEvent joined(Long userId, Role role) {
        return new UserJoinedEvent(new UserDto(userId, "tester" + userId, role, UserStatus.ACTIVE));
    }

    // 지갑의 네 가지 잔액이 모두 0인지 확인한다.
    private void assertEmptyBalances(Wallet wallet) {
        assertThat(wallet.getChargeBalanceHp()).isZero();
        assertThat(wallet.getRevenueBalanceHp()).isZero();
        assertThat(wallet.getHeldChargeHp()).isZero();
        assertThat(wallet.getHeldRevenueHp()).isZero();
    }

    // 회원 ID의 지갑 행 수를 DB에서 직접 센다.
    private int walletCount(Long userId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM billing.wallet WHERE user_id = ?", Integer.class, userId);
    }

    // 모든 작업을 같은 시점에 시작시키고 결과를 모은다.
    private <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    await(start);
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    // 다른 트랜잭션의 락을 기다리는 세션이 생길 때까지 기다린다.
    private void waitUntilLockWaiting() throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            Integer waiting = jdbcTemplate.queryForObject("SELECT count(*) FROM pg_locks WHERE NOT granted", Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("지갑 생성 트랜잭션이 락 대기 상태가 되지 않았습니다.");
    }

    // 래치가 열릴 때까지 최대 10초 기다린다.
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("대기 시간이 지났습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
