package com.hapbang.auxiliary.notification.app;

import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

import com.hapbang.auxiliary.notification.app.NotificationUserSyncResult.Outcome;
import com.hapbang.auxiliary.notification.app.exception.NotificationErrorCode;
import com.hapbang.auxiliary.notification.app.exception.NotificationException;
import com.hapbang.auxiliary.notification.out.NotificationUserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 회원 이벤트를 알림 회원 복제본({@code notification.notification_user})에 반영하는 규칙(ND-018, ND-049, test-spec NT-USR-01·02).
 * 가입은 없으면 만들고 있으면 그대로 둔다. 변경은 없으면 그 정보로 만들고, 있으면 더 큰 버전만 반영한다.
 */
@IntegrationTest
class NotificationUserSyncServiceTest {

    private static final AtomicLong NEXT_ID = new AtomicLong(1_000);

    @Autowired
    private NotificationUserSyncService syncService;

    @Autowired
    private NotificationUserRepository repository;

    @Test
    void 가입하면_만들고_같은_가입을_다시_받으면_건너뛴다() {
        long id = NEXT_ID.incrementAndGet();

        assertThat(syncService.syncJoined(user(id, "게스트", 0L)).outcome()).isEqualTo(Outcome.CREATED);
        assertThat(syncService.syncJoined(user(id, "게스트", 0L)).outcome()).isEqualTo(Outcome.SKIPPED_EXISTING);

        assertThat(repository.count()).isEqualTo(1L);
        assertThat(repository.findById(id)).hasValueSatisfying(user -> {
            assertThat(user.getNickname()).isEqualTo("게스트");
            assertThat(user.getMemberVersion()).isZero();
        });
    }

    @Test
    void 기존_복제본의_변경은_더_큰_버전만_반영한다() {
        long id = NEXT_ID.incrementAndGet();
        syncService.syncJoined(user(id, "가입", 1L));

        assertThat(syncService.syncModified(user(id, "최신", 3L)).outcome()).isEqualTo(Outcome.UPDATED);

        assertThat(repository.findById(id)).hasValueSatisfying(user -> {
            assertThat(user.getNickname()).isEqualTo("최신");
            assertThat(user.getMemberVersion()).isEqualTo(3L);
        });
    }

    @Test
    void 같은_버전의_변경을_다시_받거나_낮은_버전이_늦게_와도_최신_정보를_덮어쓰지_않는다() {
        long id = NEXT_ID.incrementAndGet();
        syncService.syncJoined(user(id, "가입", 1L));
        syncService.syncModified(user(id, "최신", 3L));

        assertThat(syncService.syncModified(user(id, "최신", 3L)).outcome()).isEqualTo(Outcome.SKIPPED_STALE);
        assertThat(syncService.syncModified(user(id, "옛것", 2L)).outcome()).isEqualTo(Outcome.SKIPPED_STALE);

        assertThat(repository.findById(id)).hasValueSatisfying(user -> {
            assertThat(user.getNickname()).isEqualTo("최신");
            assertThat(user.getMemberVersion()).isEqualTo(3L);
        });
    }

    @Test
    void 복제본이_없을_때_온_변경은_그_회원_정보로_복제본을_만든다() {
        long id = NEXT_ID.incrementAndGet();

        assertThat(syncService.syncModified(new UserDto(id, "오래된회원", Role.VERIFIED_INFLUENCER, UserStatus.ACTIVE, 7L))
                .outcome()).isEqualTo(Outcome.CREATED_FROM_MODIFIED);

        assertThat(repository.findById(id)).hasValueSatisfying(user -> {
            assertThat(user.getNickname()).isEqualTo("오래된회원");
            assertThat(user.getRole()).isEqualTo(Role.VERIFIED_INFLUENCER);
            assertThat(user.getMemberVersion()).isEqualTo(7L);
        });
    }

    @Test
    void 변경으로_만든_뒤_늦게_온_가입은_건너뛰어_최신_정보를_지킨다() {
        long id = NEXT_ID.incrementAndGet();
        syncService.syncModified(new UserDto(id, "변경후", Role.INFLUENCER, UserStatus.ACTIVE, 2L));

        assertThat(syncService.syncJoined(new UserDto(id, "가입때", Role.USER, UserStatus.ACTIVE, 0L)).outcome())
                .isEqualTo(Outcome.SKIPPED_EXISTING);

        assertThat(repository.findById(id)).hasValueSatisfying(user -> {
            assertThat(user.getNickname()).isEqualTo("변경후");
            assertThat(user.getRole()).isEqualTo(Role.INFLUENCER);
            assertThat(user.getMemberVersion()).isEqualTo(2L);
        });
    }

    @Test
    void 같은_회원의_가입과_변경을_동시에_처리해도_한_행이고_다시_처리하면_최신_버전이_남는다() throws Exception {
        long id = NEXT_ID.incrementAndGet();
        UserDto joined = new UserDto(id, "가입때", Role.USER, UserStatus.ACTIVE, 0L);
        UserDto modified = new UserDto(id, "변경후", Role.INFLUENCER, UserStatus.ACTIVE, 1L);
        List<Callable<NotificationUserSyncResult>> calls = List.of(
                () -> syncService.syncJoined(joined), () -> syncService.syncModified(modified),
                () -> syncService.syncModified(modified));

        // 동시에 시작해 생성 충돌(PK)·갱신 충돌(낙관적 락)을 일으킨다. 실패한 처리는 Kafka 블로킹 재시도처럼 한 번 더 한다
        List<Callable<NotificationUserSyncResult>> failed = runTogether(calls);
        for (Callable<NotificationUserSyncResult> retry : failed) {
            retry.call();
        }

        assertThat(repository.count()).isEqualTo(1L);
        assertThat(repository.findById(id)).hasValueSatisfying(user -> {
            assertThat(user.getNickname()).isEqualTo("변경후");
            assertThat(user.getMemberVersion()).isEqualTo(1L);
        });
    }

    @Test
    void 파기된_복제본은_뒤에_오는_가입_변경으로_바뀌지_않는다() {
        long id = NEXT_ID.incrementAndGet();
        repository.save(com.hapbang.auxiliary.notification.domain.NotificationUser.createMasked(id,
                java.time.Instant.parse("2026-11-10T00:00:00Z")));

        assertThat(syncService.syncJoined(user(id, "원래닉네임", 0L)).outcome()).isEqualTo(Outcome.SKIPPED_EXISTING);
        assertThat(syncService.syncModified(user(id, "원래닉네임", 9L)).outcome()).isEqualTo(Outcome.SKIPPED_MASKED);

        assertThat(repository.findById(id)).hasValueSatisfying(user -> {
            assertThat(user.getNickname()).isEqualTo("탈퇴회원#" + id);
            assertThat(user.isWithdrawn()).isTrue();
        });
    }

    @Test
    void 복제할_수_없는_정보는_가입이든_변경이든_잘못된_요청으로_실패하고_만들지_않는다() {
        long joinedId = NEXT_ID.incrementAndGet();
        long modifiedId = NEXT_ID.incrementAndGet();

        assertThatThrownBy(() -> syncService.syncJoined(new UserDto(joinedId, null, Role.USER, UserStatus.ACTIVE, 1L)))
                .isInstanceOfSatisfying(NotificationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(NotificationErrorCode.INVALID_MEMBER_EVENT));
        assertThatThrownBy(() -> syncService.syncModified(new UserDto(modifiedId, "x", null, UserStatus.ACTIVE, 1L)))
                .isInstanceOfSatisfying(NotificationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(NotificationErrorCode.INVALID_MEMBER_EVENT));
        assertThat(repository.findAllById(List.of(joinedId, modifiedId))).isEmpty();
    }

    /** 처리들을 동시에 시작하고, 동시 반영 충돌로 실패한 처리만 돌려준다. 그 밖의 실패는 테스트 실패다 */
    private static List<Callable<NotificationUserSyncResult>> runTogether(
            List<Callable<NotificationUserSyncResult>> calls) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<NotificationUserSyncResult>> futures = new ArrayList<>();
            for (Callable<NotificationUserSyncResult> call : calls) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<Callable<NotificationUserSyncResult>> failed = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                try {
                    futures.get(i).get(30, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOfAny(DataIntegrityViolationException.class,
                            OptimisticLockingFailureException.class);
                    failed.add(calls.get(i));
                }
            }
            return failed;
        } finally {
            executor.shutdownNow();
        }
    }

    private static UserDto user(long id, String nickname, Long version) {
        return new UserDto(id, nickname, Role.INFLUENCER, UserStatus.ACTIVE, version);
    }
}
