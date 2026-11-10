package com.hapbang.auxiliary.notification.app;

import static com.hapbang.auxiliary.notification.support.NotificationFixtures.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.auxiliary.notification.support.TestNotificationTemplate;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 같은 알림 행을 쓰는 처리끼리 서로의 변경을 덮어쓰지 않는지 본다(#801 PR 리뷰, ND-022·ND-051·ND-054, test-spec NT-RET-05·NT-USR-07).
 * <p>
 * 실행 순서를 latch로 강제한다. 먼저 시작한 쪽(T1)은 바깥 트랜잭션 안에서 처리를 마친 뒤 커밋하지 않고 기다리고, 그동안 다른 쪽(T2)이
 * 같은 행을 바꿔 커밋하게 한다. T2가 T1의 잠금에 막히면 T1은 정해진 시간 뒤 커밋하고 T2가 이어서 끝난다. 어느 쪽이든 마지막 상태에서
 * 두 변경이 모두 남아야 한다.
 */
@IntegrationTest
@Import(TestNotificationTemplate.Config.class)
class NotificationConcurrencyTest {

    private static final AtomicLong NEXT_ID = new AtomicLong(40_000);
    private static final Instant T0 = Instant.parse("2026-11-10T00:00:00Z");
    /** T2가 T1의 잠금에 막혔을 때 T1이 기다리는 시간. T2가 막히지 않으면 그보다 먼저 끝난다 */
    private static final long HOLD_SECONDS = 3;

    @Autowired
    private NotificationWriter writer;

    @Autowired
    private NotificationQueryFacade facade;

    @Autowired
    private NotificationMaskingService maskingService;

    @Autowired
    private NotificationUserSyncService userSync;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 읽음_처리가_커밋되기_전에_보존_정리가_그_알림을_밀어내도_삭제_시각이_남는다() throws Exception {
        long receiver = member();
        long oldest = fill(receiver, 20);

        // T1: 가장 오래된 알림을 읽음 처리하고 커밋 전에 기다린다. T2: 21번째를 저장해 그 알림을 밀어낸다
        race(() -> {
            asReceiver(receiver);
            facade.markRead(receiver, oldest);
        }, () -> writer.save(draft(receiver, "s-new", 7L, "호스트", T0.plusSeconds(100))));

        assertThat(column(oldest, "deleted_at")).isNotNull();
        assertThat(column(oldest, "read_at")).isNotNull();
        assertThat(aliveCount(receiver)).isEqualTo(20);
    }

    @Test
    void 보존_정리가_커밋되기_전에_파기가_커밋돼도_파기된_닉네임이_되돌아가지_않는다() throws Exception {
        long receiver = member();
        long masked = NEXT_ID.incrementAndGet();
        long oldest = writer.save(draft(receiver, "s-0", masked, "원래닉네임", T0)).notificationId();
        for (int i = 1; i < 20; i++) {
            writer.save(draft(receiver, "s-" + i, 7L, "호스트", T0.plusSeconds(i)));
        }

        // T1: 21번째를 저장해 가장 오래된 알림을 밀어내고 커밋 전에 기다린다. T2: 그 알림에 언급된 회원을 파기한다
        race(() -> writer.save(draft(receiver, "s-new", 7L, "호스트", T0.plusSeconds(100))),
                () -> maskingService.mask(masked));

        assertThat((String) column(oldest, "message")).doesNotContain("원래닉네임").contains("탈퇴회원#" + masked);
        assertThat(String.valueOf(column(oldest, "payload"))).doesNotContain("원래닉네임");
        assertThat(column(oldest, "deleted_at")).isNotNull();
    }

    @Test
    void 보존_정리가_커밋되기_전에_전체_읽음이_커밋돼도_읽음으로_센_알림의_읽은_시각이_남는다() throws Exception {
        long receiver = member();
        fill(receiver, 20);
        AtomicInteger updated = new AtomicInteger();

        // T1: 21번째를 저장해 가장 오래된 알림을 밀어내고 커밋 전에 기다린다. T2: 전체 읽음
        race(() -> writer.save(draft(receiver, "s-new", 7L, "호스트", T0.plusSeconds(100))), () -> {
            asReceiver(receiver);
            updated.set(facade.markAllRead(receiver).updatedCount());
        });

        // 전체 읽음이 읽음으로 바꿨다고 센 알림 수와 실제로 읽은 시각이 남은 알림 수가 같다(덮어써 지워진 것이 없다)
        assertThat(readCount(receiver)).isEqualTo(updated.get());
        assertThat(aliveCount(receiver)).isEqualTo(20);
    }

    @Test
    void 두_회원이_함께_언급된_알림에_두_파기가_겹쳐도_두_닉네임_모두_파기된다() throws Exception {
        long receiver = member();
        long b = NEXT_ID.incrementAndGet();
        long c = NEXT_ID.incrementAndGet();
        long id = writer.save(draftMentioning(receiver, "both", b, "비원래", c, "씨원래")).notificationId();

        // T1: B를 파기하고 커밋 전에 기다린다. T2: C를 파기한다
        race(() -> maskingService.mask(b), () -> maskingService.mask(c));

        String payload = String.valueOf(column(id, "payload"));
        assertThat(payload).doesNotContain("비원래").doesNotContain("씨원래")
                .contains("탈퇴회원#" + b).contains("탈퇴회원#" + c);
        assertThat((String) column(id, "message")).doesNotContain("비원래");
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * T1을 바깥 트랜잭션에서 실행하고, 커밋 전에 T2가 끝나기를 기다린다(T2가 T1의 잠금에 막히면 {@value #HOLD_SECONDS}초 뒤 커밋).
     * T2는 T1의 처리가 끝난 뒤 시작한다.
     */
    private void race(Runnable first, Runnable second) throws Exception {
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CompletableFuture<Void> t1 = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            first.run();
            firstDone.countDown();
            await(secondDone);
        }));
        assertThat(firstDone.await(30, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Void> t2 = CompletableFuture.runAsync(() -> {
            try {
                second.run();
            } finally {
                secondDone.countDown();
            }
        });
        t1.get(60, TimeUnit.SECONDS);
        t2.get(60, TimeUnit.SECONDS);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(HOLD_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void asReceiver(long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(String.valueOf(userId), null, "ROLE_" + Role.USER.name()));
    }

    private long member() {
        long id = NEXT_ID.incrementAndGet();
        userSync.syncJoined(new UserDto(id, "회원" + id, Role.USER, UserStatus.ACTIVE, 0L));
        return id;
    }

    /** s-0(가장 오래됨)부터 count개를 저장하고 가장 오래된 알림 ID를 돌려준다 */
    private long fill(long receiver, int count) {
        long oldest = writer.save(draft(receiver, "s-0", 7L, "호스트", T0)).notificationId();
        for (int i = 1; i < count; i++) {
            writer.save(draft(receiver, "s-" + i, 7L, "호스트", T0.plusSeconds(i)));
        }
        return oldest;
    }

    private Object column(long id, String name) {
        return jdbcTemplate.queryForObject("select " + name + " from notification.notification where id = ?",
                Object.class, id);
    }

    private int aliveCount(long receiver) {
        return count("select count(*) from notification.notification where receiver_user_id = ? and deleted_at is null",
                receiver);
    }

    private int readCount(long receiver) {
        return count("select count(*) from notification.notification where receiver_user_id = ? and read_at is not null",
                receiver);
    }

    private int count(String sql, long receiver) {
        Supplier<Integer> query = () -> jdbcTemplate.queryForObject(sql, Integer.class, receiver);
        return query.get();
    }
}
