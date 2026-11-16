package com.hapbang.auxiliary.notification.app;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.hapbang.auxiliary.notification.app.exception.NotificationErrorCode;
import com.hapbang.auxiliary.notification.app.exception.NotificationException;
import com.hapbang.auxiliary.notification.domain.ScheduleKind;
import com.hapbang.auxiliary.notification.domain.ScheduleTimes;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 예약 생성·취소(ND-029·ND-034·ND-136·ND-138). 가장 중요한 검증: 같은 예약에 대한 생성·취소 순서 역전.
 * test-spec NT-SCH-05·06·09·13, NT-USR-03(예약).
 */
@IntegrationTest
class ScheduleServiceTest {

    /** 다른 테스트 클래스와 겹치지 않는 회원·대상 ID 범위(같은 DB를 함께 쓴다) */
    private static final AtomicLong NEXT_ID = new AtomicLong(928_000);
    private static final Instant NOW = Instant.parse("2026-11-13T00:00:00Z");
    private static final ScheduleTimes FUTURE = new ScheduleTimes(NOW.plusSeconds(3_600), NOW.plusSeconds(7_200));

    @Autowired
    private ScheduleService schedules;

    @Autowired
    private NotificationUserSyncService userSync;

    @Autowired
    private JdbcTemplate jdbc;

    // NT-SCH-06: 5종 모두(ND-138)

    @ParameterizedTest
    @EnumSource(ScheduleKind.class)
    void 취소가_먼저_오면_취소_행이_남고_늦게_온_생성은_예약을_만들지_않는다(ScheduleKind kind) {
        long receiver = member(UserStatus.ACTIVE);
        long target = NEXT_ID.incrementAndGet();

        schedules.cancel(cancel(kind, target, receiver), NOW);
        schedules.create(create(kind, target, receiver, FUTURE), NOW);

        assertThat(rows(kind, target, receiver)).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("status", "CANCELED"));
    }

    // NT-SCH-05

    @Test
    void 생성_뒤_취소가_오면_취소된다_다른_받는_사람과_다른_대상은_그대로다() {
        long host = member(UserStatus.ACTIVE);
        long guest = member(UserStatus.ACTIVE);
        long target = NEXT_ID.incrementAndGet();
        long other = NEXT_ID.incrementAndGet();
        ScheduleKind kind = ScheduleKind.APPEARANCE_STARTING;
        schedules.create(create(kind, target, host, FUTURE), NOW);
        schedules.create(create(kind, target, guest, FUTURE), NOW);
        schedules.create(create(kind, other, host, FUTURE), NOW);

        schedules.cancel(cancel(kind, target, host), NOW);

        assertThat(status(kind, target, host)).isEqualTo("CANCELED");
        assertThat(status(kind, target, guest)).isEqualTo("SCHEDULED");
        assertThat(status(kind, other, host)).isEqualTo("SCHEDULED");
    }

    // NT-SCH-09

    @Test
    void 같은_생성을_두_번_받아도_예약은_한_건이고_처음_값을_지킨다() {
        long receiver = member(UserStatus.ACTIVE);
        long target = NEXT_ID.incrementAndGet();
        ScheduleKind kind = ScheduleKind.APPEARANCE_REQUEST_EXPIRING;

        schedules.create(create(kind, target, receiver, FUTURE), NOW);
        schedules.create(create(kind, target, receiver,
                new ScheduleTimes(NOW.plusSeconds(60), NOW.plusSeconds(120))), NOW);

        assertThat(rows(kind, target, receiver)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("status", "SCHEDULED");
            assertThat(((java.sql.Timestamp) row.get("send_at")).toInstant()).isEqualTo(FUTURE.sendAt());
        });
    }

    @Test
    void 이미_보냈거나_건너뛴_예약에_취소가_와도_상태를_바꾸지_않는다() {
        long receiver = member(UserStatus.ACTIVE);
        long sent = NEXT_ID.incrementAndGet();
        long skipped = NEXT_ID.incrementAndGet();
        ScheduleKind kind = ScheduleKind.LECTURE_ORDER_ITEM_CONFIRMING;
        schedules.create(create(kind, sent, receiver, FUTURE), NOW);
        jdbc.update("update notification.notification_schedule set status = 'SENT' where kind = ? and target_id = ?",
                kind.name(), sent);
        schedules.create(create(kind, skipped, receiver, new ScheduleTimes(NOW.minusSeconds(120),
                NOW.minusSeconds(60))), NOW);

        schedules.cancel(cancel(kind, sent, receiver), NOW);
        schedules.cancel(cancel(kind, skipped, receiver), NOW);
        schedules.cancel(cancel(kind, sent, receiver), NOW);

        assertThat(status(kind, sent, receiver)).isEqualTo("SENT");
        assertThat(status(kind, skipped, receiver)).isEqualTo("SKIPPED");
    }

    // NT-SCH-13 (ND-136·ND-144)

    @Test
    void 업무_기한이_지난_생성은_건너뜀_행만_남기고_재전달해도_한_건이다() {
        long receiver = member(UserStatus.ACTIVE);
        long target = NEXT_ID.incrementAndGet();
        ScheduleKind kind = ScheduleKind.APPEARANCE_RECRUIT_CLOSING;
        ScheduleTimes atDeadline = new ScheduleTimes(NOW.minusSeconds(3_600), NOW);

        schedules.create(create(kind, target, receiver, atDeadline), NOW);
        schedules.create(create(kind, target, receiver, atDeadline), NOW);

        assertThat(rows(kind, target, receiver)).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("status", "SKIPPED"));
    }

    @Test
    void 보낼_시각이_지났어도_업무_기한_전이면_예약으로_만든다() {
        long receiver = member(UserStatus.ACTIVE);
        long target = NEXT_ID.incrementAndGet();
        ScheduleKind kind = ScheduleKind.APPEARANCE_REQUEST_EXPIRING;

        schedules.create(create(kind, target, receiver, new ScheduleTimes(NOW.minusSeconds(60), NOW.plusSeconds(1))),
                NOW);

        assertThat(status(kind, target, receiver)).isEqualTo("SCHEDULED");
    }

    // NT-USR-03(예약), ND-035

    @Test
    void 받을_수_없는_회원에게는_예약을_만들지_않는다() {
        long withdrawn = member(UserStatus.WITHDRAWN);
        long suspended = member(UserStatus.SUSPENDED);
        long target = NEXT_ID.incrementAndGet();
        ScheduleKind kind = ScheduleKind.APPEARANCE_STARTING;

        schedules.create(create(kind, target, withdrawn, FUTURE), NOW);
        schedules.create(create(kind, target, suspended, FUTURE), NOW);

        assertThat(rows(kind, target, withdrawn)).isEmpty();
        assertThat(rows(kind, target, suspended)).isEmpty();
    }

    @Test
    void 받는_사람_복제본이_없으면_생성은_실패하고_취소는_복제본_없이도_행을_남긴다() {
        long unknown = NEXT_ID.incrementAndGet();
        long target = NEXT_ID.incrementAndGet();
        ScheduleKind kind = ScheduleKind.APPEARANCE_STARTING;

        assertThatThrownBy(() -> schedules.create(create(kind, target, unknown, FUTURE), NOW))
                .isInstanceOfSatisfying(NotificationException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(NotificationErrorCode.RECEIVER_NOT_REPLICATED));
        schedules.cancel(cancel(kind, target, unknown), NOW);

        assertThat(status(kind, target, unknown)).isEqualTo("CANCELED");
    }

    // ND-145: 모듈 시험의 알림 시작 시각은 2000-01-01T00:00:00Z다(hapbang-module-test.yaml)

    @Test
    void 알림_시작_시각보다_이른_사건이면_받는_사람_복제본이_없어도_예약을_만들지_않고_실패하지_않는다() {
        long unknown = NEXT_ID.incrementAndGet();
        long target = NEXT_ID.incrementAndGet();
        ScheduleKind kind = ScheduleKind.APPEARANCE_STARTING;

        schedules.create(new ScheduleCommand.Create(kind, target, unknown, FUTURE, Map.of("v", 1),
                Instant.parse("1999-12-31T23:59:59Z")), NOW);

        assertThat(rows(kind, target, unknown)).isEmpty();
    }

    @Test
    void 알림_시작_시각과_같은_사건이면_예약을_만든다() {
        long receiver = member(UserStatus.ACTIVE);
        long target = NEXT_ID.incrementAndGet();
        ScheduleKind kind = ScheduleKind.APPEARANCE_STARTING;

        schedules.create(new ScheduleCommand.Create(kind, target, receiver, FUTURE, Map.of("v", 1),
                Instant.parse("2000-01-01T00:00:00Z")), NOW);

        assertThat(status(kind, target, receiver)).isEqualTo("SCHEDULED");
    }

    // 동시 생성·취소: 어느 쪽이 먼저 커밋해도 최종은 취소, 행은 하나(유일 키 + 충돌 처리)

    @Test
    void 같은_예약의_생성과_취소가_동시에_와도_최종_상태는_취소이고_행은_하나다() throws Exception {
        long receiver = member(UserStatus.ACTIVE);
        ScheduleKind kind = ScheduleKind.APPEARANCE_CONFIRM_PERIOD_ENDING;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 20; round++) {
                long target = NEXT_ID.incrementAndGet();
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> futures = new ArrayList<>();
                futures.add(pool.submit(() -> {
                    start.await();
                    schedules.create(create(kind, target, receiver, FUTURE), NOW);
                    return null;
                }));
                futures.add(pool.submit(() -> {
                    start.await();
                    schedules.cancel(cancel(kind, target, receiver), NOW);
                    return null;
                }));
                start.countDown();
                for (Future<?> future : futures) {
                    future.get(10, TimeUnit.SECONDS);
                }

                assertThat(rows(kind, target, receiver)).as("round %d", round).singleElement()
                        .satisfies(row -> assertThat(row).containsEntry("status", "CANCELED"));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // 리스너 처리 단위: 예약 처리와 즉시 알림은 한 트랜잭션(ND-133)

    @Test
    void 함께_처리하는_즉시_알림이_실패하면_예약_생성과_취소도_되돌린다() {
        long receiver = member(UserStatus.ACTIVE);
        long created = NEXT_ID.incrementAndGet();
        long canceled = NEXT_ID.incrementAndGet();
        ScheduleKind kind = ScheduleKind.APPEARANCE_STARTING;
        schedules.create(create(kind, canceled, receiver, FUTURE), NOW);

        assertThatThrownBy(() -> schedules.applyWith(
                List.of(create(kind, created, receiver, FUTURE), cancel(kind, canceled, receiver)),
                () -> {
                    throw new IllegalStateException("알림 저장 실패");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(rows(kind, created, receiver)).isEmpty();
        assertThat(status(kind, canceled, receiver)).isEqualTo("SCHEDULED");
    }

    @Test
    void 예약_처리와_즉시_알림이_모두_성공하면_함께_커밋한다() {
        long receiver = member(UserStatus.ACTIVE);
        long target = NEXT_ID.incrementAndGet();
        ScheduleKind kind = ScheduleKind.APPEARANCE_STARTING;
        List<String> ran = new ArrayList<>();

        schedules.applyWith(List.of(create(kind, target, receiver, FUTURE)), () -> ran.add("alert"));

        assertThat(ran).containsExactly("alert");
        assertThat(rows(kind, target, receiver)).hasSize(1);
    }

    private ScheduleCommand.Create create(ScheduleKind kind, long target, long receiver, ScheduleTimes times) {
        return new ScheduleCommand.Create(kind, target, receiver, times, Map.of("v", 1, "title", "제목 " + target), NOW);
    }

    private ScheduleCommand.Cancel cancel(ScheduleKind kind, long target, long receiver) {
        return new ScheduleCommand.Cancel(kind, target, receiver, FUTURE.businessDeadline());
    }

    private List<Map<String, Object>> rows(ScheduleKind kind, long target, long receiver) {
        return jdbc.queryForList("""
                select status, send_at, business_deadline from notification.notification_schedule
                where kind = ? and target_id = ? and receiver_user_id = ?
                """, kind.name(), target, receiver);
    }

    private String status(ScheduleKind kind, long target, long receiver) {
        return (String) rows(kind, target, receiver).getFirst().get("status");
    }

    private long member(UserStatus status) {
        long id = NEXT_ID.incrementAndGet();
        userSync.syncJoined(new UserDto(id, "회원" + id, Role.INFLUENCER, status, 0L));
        return id;
    }
}
