package com.hapbang.auxiliary.notification.app;

import static org.assertj.core.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.auxiliary.global.scheduling.JobLock;
import com.hapbang.auxiliary.notification.domain.ScheduleKind;
import com.hapbang.auxiliary.notification.domain.ScheduleTimes;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 예약 배치(ND-029·ND-135·ND-142·ND-144). 가장 중요한 검증: 알림 저장과 예약 상태 변경은 한 트랜잭션이다. 둘 중 하나가 실패하면 둘 다
 * 되돌아가 예약은 "예약"으로 남고 다음 배치가 다시 보낸다. test-spec NT-SCH-07·08·10·12·14·16.
 * <p>
 * 다른 테스트 클래스의 예약(2026년)과 섞이지 않게 2001년 시각을 쓴다.
 */
@IntegrationTest
class ScheduleDispatcherTest {

    private static final AtomicLong NEXT_ID = new AtomicLong(928_500);
    private static final Instant NOW = Instant.parse("2001-01-10T00:00:00Z");

    @Autowired
    private ScheduleDispatcher dispatcher;

    @Autowired
    private ScheduleService schedules;

    @Autowired
    private NotificationUserSyncService userSync;

    @Autowired
    private JobLock jobLock;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transaction;

    // NT-SCH-08·16

    @Test
    void 보낼_시각이_지난_예약만_알림으로_만들고_보냄으로_바꾼다() {
        long receiver = member();
        long due = NEXT_ID.incrementAndGet();
        long later = NEXT_ID.incrementAndGet();
        create(ScheduleKind.APPEARANCE_STARTING, due, receiver, NOW.minusSeconds(1), NOW.plusSeconds(86_400),
                deadlinePayload("합방"));
        create(ScheduleKind.APPEARANCE_STARTING, later, receiver, NOW.plusSeconds(60), NOW.plusSeconds(86_400),
                deadlinePayload("합방"));

        long id = scheduleId(ScheduleKind.APPEARANCE_STARTING, due, receiver);
        assertThat(dispatcher.dispatchOne(id, at(NOW))).isEqualTo(ScheduleDispatcher.Outcome.SENT);

        assertThat(status(ScheduleKind.APPEARANCE_STARTING, due, receiver)).isEqualTo("SENT");
        assertThat(status(ScheduleKind.APPEARANCE_STARTING, later, receiver)).isEqualTo("SCHEDULED");
        assertThat(alertsOf(receiver)).singleElement().satisfies(alert -> assertThat(alert)
                .containsEntry("kind", "APPEARANCE_STARTING")
                .containsEntry("source_event_type", "APPEARANCE_STARTING")
                .containsEntry("source_target_id", String.valueOf(due))
                .containsEntry("target_type", "APPEARANCE")
                .containsEntry("target_id", String.valueOf(due))
                .containsEntry("message", "'합방' 방송이 1월 11일 09:00에 시작됩니다."));
        // 발생 시각은 보낼 시각이 아니라 보낸 시각이다(ND-148)
        assertThat(occurredAtOf(receiver)).isEqualTo(NOW);
    }

    @Test
    void 늦게_보낸_예약_알림도_보낸_시각으로_정렬돼_20개_보존에서_밀려나지_않는다() {
        long receiver = member();
        long target = NEXT_ID.incrementAndGet();
        Instant sentAt = NOW.plusSeconds(30_000);
        // 보낼 시각(NOW - 1일)보다 새로운 다른 알림 20개
        for (int i = 0; i < 20; i++) {
            long other = NEXT_ID.incrementAndGet();
            create(ScheduleKind.APPEARANCE_RECRUIT_CLOSING, other, receiver, NOW.minusSeconds(3_600 - i),
                    NOW.plusSeconds(86_400), deadlinePayload("모집" + i));
            dispatcher.dispatchOne(scheduleId(ScheduleKind.APPEARANCE_RECRUIT_CLOSING, other, receiver), at(NOW));
        }
        create(ScheduleKind.APPEARANCE_STARTING, target, receiver, NOW.minusSeconds(86_400), NOW.plusSeconds(86_400),
                deadlinePayload("합방"));

        dispatcher.dispatchOne(scheduleId(ScheduleKind.APPEARANCE_STARTING, target, receiver), at(sentAt));

        assertThat(jdbc.queryForObject("""
                select deleted_at is null from notification.notification
                where receiver_user_id = ? and kind = 'APPEARANCE_STARTING'
                """, Boolean.class, receiver)).isTrue();
    }

    // ND-148·ND-135 (#929 리뷰 3): 회차 시작 시각이 아니라 행을 잠근 뒤의 시각으로 판정하고 그 시각을 발생 시각으로 쓴다

    @Test
    void 회차_시작에는_기한_전이었어도_행을_잠근_뒤_기한이_지났으면_건너뛴다() {
        long receiver = member();
        long target = NEXT_ID.incrementAndGet();
        Instant selectedAt = Instant.parse("2004-01-01T00:00:00Z");
        create(ScheduleKind.APPEARANCE_REQUEST_EXPIRING, target, receiver, selectedAt.minusSeconds(60),
                selectedAt.plusSeconds(1), deadlinePayload("요청"));

        dispatcher.runOnce(stepping(selectedAt, selectedAt.plusSeconds(2)));

        assertThat(status(ScheduleKind.APPEARANCE_REQUEST_EXPIRING, target, receiver)).isEqualTo("SKIPPED");
        assertThat(alertsOf(receiver)).isEmpty();
    }

    @Test
    void 예약_알림의_발생_시각은_회차_시작이_아니라_보낸_시각이다() {
        long receiver = member();
        long target = NEXT_ID.incrementAndGet();
        Instant selectedAt = Instant.parse("2004-02-01T00:00:00Z");
        Instant sentAt = selectedAt.plusSeconds(5);
        create(ScheduleKind.APPEARANCE_STARTING, target, receiver, selectedAt.minusSeconds(60),
                selectedAt.plusSeconds(86_400), deadlinePayload("합방"));

        dispatcher.runOnce(stepping(selectedAt, sentAt));

        assertThat(status(ScheduleKind.APPEARANCE_STARTING, target, receiver)).isEqualTo("SENT");
        assertThat(occurredAtOf(receiver)).isEqualTo(sentAt);
    }

    // ND-147: 같은 예약이 계속 실패하면

    @Test
    void 같은_예약이_실패하면_롤백과_따로_실패_횟수를_세고_3번_연속이면_건너뜀으로_끝낸다() {
        long receiver = member();
        long target = NEXT_ID.incrementAndGet();
        Instant now = Instant.parse("2002-01-01T00:00:00Z");
        // 기한 값이 없어 문구를 만들지 못한다(알림 저장 실패)
        create(ScheduleKind.APPEARANCE_STARTING, target, receiver, now.minusSeconds(60), now.plusSeconds(86_400),
                Map.of("v", 1, "title", "합방"));
        long id = scheduleId(ScheduleKind.APPEARANCE_STARTING, target, receiver);

        dispatcher.runOnce(at(now));
        assertThat(failureCount(id)).isEqualTo(1);
        assertThat(status(ScheduleKind.APPEARANCE_STARTING, target, receiver)).isEqualTo("SCHEDULED");

        dispatcher.runOnce(at(now.plusSeconds(60)));
        assertThat(failureCount(id)).isEqualTo(2);
        assertThat(status(ScheduleKind.APPEARANCE_STARTING, target, receiver)).isEqualTo("SCHEDULED");

        ScheduleDispatcher.RunResult third = dispatcher.runOnce(at(now.plusSeconds(120)));
        assertThat(failureCount(id)).isEqualTo(3);
        assertThat(status(ScheduleKind.APPEARANCE_STARTING, target, receiver)).isEqualTo("SKIPPED");
        assertThat(third.failed()).isGreaterThanOrEqualTo(1);
        assertThat(alertsOf(receiver)).isEmpty();

        // 끝난 예약은 다음 회차가 다시 고르지 않는다
        dispatcher.runOnce(at(now.plusSeconds(180)));
        assertThat(failureCount(id)).isEqualTo(3);
    }

    @Test
    void 같은_출연의_방송_임박과_출연_확인_부탁은_원본_대상이_같아도_따로_알린다() {
        long host = member();
        long appearance = NEXT_ID.incrementAndGet();
        create(ScheduleKind.APPEARANCE_STARTING, appearance, host, NOW.minusSeconds(2), NOW.plusSeconds(86_400),
                deadlinePayload("합방"));
        create(ScheduleKind.APPEARANCE_CONFIRM_PERIOD_ENDING, appearance, host, NOW.minusSeconds(1),
                NOW.plusSeconds(86_400), deadlinePayload("합방"));

        dispatcher.dispatchOne(scheduleId(ScheduleKind.APPEARANCE_STARTING, appearance, host), at(NOW));
        dispatcher.dispatchOne(scheduleId(ScheduleKind.APPEARANCE_CONFIRM_PERIOD_ENDING, appearance, host), at(NOW));

        assertThat(alertsOf(host)).extracting(alert -> alert.get("kind"))
                .containsExactlyInAnyOrder("APPEARANCE_STARTING", "APPEARANCE_CONFIRM_PERIOD_ENDING");
    }

    // NT-SCH-12 (ND-135·ND-144)

    @Test
    void 업무_기한과_같은_시각이면_알림_없이_건너뛴다() {
        long receiver = member();
        long target = NEXT_ID.incrementAndGet();
        create(ScheduleKind.APPEARANCE_REQUEST_EXPIRING, target, receiver, NOW.minusSeconds(3_600),
                NOW.plusSeconds(1), deadlinePayload("요청"));
        long id = scheduleId(ScheduleKind.APPEARANCE_REQUEST_EXPIRING, target, receiver);

        assertThat(dispatcher.dispatchOne(id, at(NOW.plusSeconds(1)))).isEqualTo(ScheduleDispatcher.Outcome.SKIPPED);

        assertThat(status(ScheduleKind.APPEARANCE_REQUEST_EXPIRING, target, receiver)).isEqualTo("SKIPPED");
        assertThat(alertsOf(receiver)).isEmpty();
    }

    @Test
    void 업무_기한_1초_전이면_보낸다() {
        long receiver = member();
        long target = NEXT_ID.incrementAndGet();
        create(ScheduleKind.APPEARANCE_REQUEST_EXPIRING, target, receiver, NOW.minusSeconds(3_600),
                NOW.plusSeconds(1), deadlinePayload("요청"));

        assertThat(dispatcher.dispatchOne(scheduleId(ScheduleKind.APPEARANCE_REQUEST_EXPIRING, target, receiver), at(NOW)))
                .isEqualTo(ScheduleDispatcher.Outcome.SENT);
    }

    @Test
    void 예약_뒤_받는_사람이_탈퇴했으면_알림_없이_건너뛴다() {
        long receiver = member();
        long target = NEXT_ID.incrementAndGet();
        create(ScheduleKind.APPEARANCE_STARTING, target, receiver, NOW.minusSeconds(1), NOW.plusSeconds(86_400),
                deadlinePayload("합방"));
        userSync.syncModified(new UserDto(receiver, "회원" + receiver, Role.INFLUENCER, UserStatus.WITHDRAWN, 1L));

        assertThat(dispatcher.dispatchOne(scheduleId(ScheduleKind.APPEARANCE_STARTING, target, receiver), at(NOW)))
                .isEqualTo(ScheduleDispatcher.Outcome.SKIPPED);

        assertThat(status(ScheduleKind.APPEARANCE_STARTING, target, receiver)).isEqualTo("SKIPPED");
        assertThat(alertsOf(receiver)).isEmpty();
    }

    // NT-SCH-07: 생성·취소 순서와 배치의 경합

    @Test
    void 배치가_대상을_읽은_뒤_보내기_전에_취소가_커밋되면_보내지_않는다() {
        long receiver = member();
        long target = NEXT_ID.incrementAndGet();
        create(ScheduleKind.APPEARANCE_STARTING, target, receiver, NOW.minusSeconds(1), NOW.plusSeconds(86_400),
                deadlinePayload("합방"));
        long id = scheduleId(ScheduleKind.APPEARANCE_STARTING, target, receiver);

        schedules.cancel(new ScheduleCommand.Cancel(ScheduleKind.APPEARANCE_STARTING, target, receiver,
                NOW.plusSeconds(86_400)), NOW);

        assertThat(dispatcher.dispatchOne(id, at(NOW))).isEqualTo(ScheduleDispatcher.Outcome.NOT_SCHEDULED);
        assertThat(status(ScheduleKind.APPEARANCE_STARTING, target, receiver)).isEqualTo("CANCELED");
        assertThat(alertsOf(receiver)).isEmpty();
    }

    @Test
    void 취소_트랜잭션이_행을_쥐고_있으면_배치는_기다렸다가_취소된_것을_보고_보내지_않는다() throws Exception {
        long receiver = member();
        long target = NEXT_ID.incrementAndGet();
        create(ScheduleKind.APPEARANCE_STARTING, target, receiver, NOW.minusSeconds(1), NOW.plusSeconds(86_400),
                deadlinePayload("합방"));
        long id = scheduleId(ScheduleKind.APPEARANCE_STARTING, target, receiver);
        CountDownLatch canceledNotCommitted = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> cancel = pool.submit(() -> transaction.executeWithoutResult(status -> {
                schedules.cancel(new ScheduleCommand.Cancel(ScheduleKind.APPEARANCE_STARTING, target, receiver,
                        NOW.plusSeconds(86_400)), NOW);
                canceledNotCommitted.countDown();
                await(commit);
            }));
            assertThat(canceledNotCommitted.await(10, TimeUnit.SECONDS)).isTrue();
            Future<ScheduleDispatcher.Outcome> dispatch = pool.submit(() -> dispatcher.dispatchOne(id, at(NOW)));
            // 배치는 행 잠금에서 기다린다
            assertThatThrownBy(() -> dispatch.get(500, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            commit.countDown();
            cancel.get(10, TimeUnit.SECONDS);

            assertThat(dispatch.get(10, TimeUnit.SECONDS)).isEqualTo(ScheduleDispatcher.Outcome.NOT_SCHEDULED);
        } finally {
            commit.countDown();
            pool.shutdownNow();
        }
        assertThat(status(ScheduleKind.APPEARANCE_STARTING, target, receiver)).isEqualTo("CANCELED");
        assertThat(alertsOf(receiver)).isEmpty();
    }

    @Test
    void 배치가_행을_먼저_쥐고_있으면_취소는_기다렸다가_이미_보낸_예약을_바꾸지_않는다() throws Exception {
        long receiver = member();
        long target = NEXT_ID.incrementAndGet();
        create(ScheduleKind.APPEARANCE_STARTING, target, receiver, NOW.minusSeconds(1), NOW.plusSeconds(86_400),
                deadlinePayload("합방"));
        long id = scheduleId(ScheduleKind.APPEARANCE_STARTING, target, receiver);
        CountDownLatch dispatchedNotCommitted = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 배치와 같은 처리(dispatchOne)를 바깥 트랜잭션 안에서 해 커밋을 붙잡는다
            Future<ScheduleDispatcher.Outcome> dispatch = pool.submit(() -> transaction.execute(status -> {
                ScheduleDispatcher.Outcome outcome = dispatcher.dispatchOne(id, at(NOW));
                dispatchedNotCommitted.countDown();
                await(commit);
                return outcome;
            }));
            assertThat(dispatchedNotCommitted.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> cancel = pool.submit(() -> schedules.cancel(new ScheduleCommand.Cancel(
                    ScheduleKind.APPEARANCE_STARTING, target, receiver, NOW.plusSeconds(86_400)), NOW));
            // 취소는 행 잠금에서 기다린다
            assertThatThrownBy(() -> cancel.get(500, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            commit.countDown();

            assertThat(dispatch.get(10, TimeUnit.SECONDS)).isEqualTo(ScheduleDispatcher.Outcome.SENT);
            cancel.get(10, TimeUnit.SECONDS);
        } finally {
            commit.countDown();
            pool.shutdownNow();
        }
        assertThat(status(ScheduleKind.APPEARANCE_STARTING, target, receiver)).isEqualTo("SENT");
        assertThat(alertsOf(receiver)).hasSize(1);
    }

    // 트랜잭션 일관성

    @Test
    void 알림_저장이_실패하면_예약은_예약으로_남고_알림도_없다() {
        long receiver = member();
        long target = NEXT_ID.incrementAndGet();
        // 기한 값이 없어 문구를 만들지 못한다(저장 단계 실패)
        create(ScheduleKind.APPEARANCE_STARTING, target, receiver, NOW.minusSeconds(1), NOW.plusSeconds(86_400),
                Map.of("v", 1, "title", "합방"));
        long id = scheduleId(ScheduleKind.APPEARANCE_STARTING, target, receiver);

        assertThatThrownBy(() -> dispatcher.dispatchOne(id, at(NOW))).isInstanceOf(RuntimeException.class);

        assertThat(status(ScheduleKind.APPEARANCE_STARTING, target, receiver)).isEqualTo("SCHEDULED");
        assertThat(alertsOf(receiver)).isEmpty();
    }

    @Test
    void 예약_상태를_보냄으로_바꾸지_못하면_저장한_알림도_되돌리고_다음_배치가_다시_보낸다() {
        long receiver = member();
        long target = NEXT_ID.incrementAndGet();
        create(ScheduleKind.APPEARANCE_STARTING, target, receiver, NOW.minusSeconds(1), NOW.plusSeconds(86_400),
                deadlinePayload("합방"));
        long id = scheduleId(ScheduleKind.APPEARANCE_STARTING, target, receiver);
        failSentUpdateOf(target);
        try {
            assertThatThrownBy(() -> dispatcher.dispatchOne(id, at(NOW))).isInstanceOf(RuntimeException.class);

            assertThat(status(ScheduleKind.APPEARANCE_STARTING, target, receiver)).isEqualTo("SCHEDULED");
            assertThat(alertsOf(receiver)).isEmpty();
        } finally {
            dropFailTrigger();
        }

        assertThat(dispatcher.dispatchOne(id, at(NOW))).isEqualTo(ScheduleDispatcher.Outcome.SENT);
        assertThat(alertsOf(receiver)).hasSize(1);
    }

    @Test
    void 같은_알림이_이미_있으면_새로_만들지_않고_보냄으로_끝낸다() {
        long receiver = member();
        long target = NEXT_ID.incrementAndGet();
        create(ScheduleKind.APPEARANCE_STARTING, target, receiver, NOW.minusSeconds(1), NOW.plusSeconds(86_400),
                deadlinePayload("합방"));
        long id = scheduleId(ScheduleKind.APPEARANCE_STARTING, target, receiver);
        dispatcher.dispatchOne(id, at(NOW));
        jdbc.update("update notification.notification_schedule set status = 'SCHEDULED' where id = ?", id);

        assertThat(dispatcher.dispatchOne(id, at(NOW))).isEqualTo(ScheduleDispatcher.Outcome.SENT);

        assertThat(alertsOf(receiver)).hasSize(1);
    }

    @Test
    void 한_번의_실행에서_한_예약이_실패해도_다른_예약은_보낸다() {
        long receiver = member();
        long bad = NEXT_ID.incrementAndGet();
        long good = NEXT_ID.incrementAndGet();
        Instant now = NOW.plusSeconds(10_000);
        create(ScheduleKind.APPEARANCE_RECRUIT_CLOSING, bad, receiver, now.minusSeconds(2), now.plusSeconds(86_400),
                Map.of("v", 1, "title", "모집"));
        create(ScheduleKind.APPEARANCE_RECRUIT_CLOSING, good, receiver, now.minusSeconds(1), now.plusSeconds(86_400),
                deadlinePayload("모집"));

        ScheduleDispatcher.RunResult result = dispatcher.runOnce(at(now));

        assertThat(result.ran()).isTrue();
        assertThat(result.failed()).isGreaterThanOrEqualTo(1);
        assertThat(status(ScheduleKind.APPEARANCE_RECRUIT_CLOSING, bad, receiver)).isEqualTo("SCHEDULED");
        assertThat(status(ScheduleKind.APPEARANCE_RECRUIT_CLOSING, good, receiver)).isEqualTo("SENT");
    }

    @Test
    void 다른_실행이_잠금을_쥐고_있으면_이번_실행은_건너뛴다() {
        long receiver = member();
        long target = NEXT_ID.incrementAndGet();
        Instant now = NOW.plusSeconds(20_000);
        create(ScheduleKind.APPEARANCE_STARTING, target, receiver, now.minusSeconds(1), now.plusSeconds(86_400),
                deadlinePayload("합방"));

        Optional<JobLock.Lease> held = jobLock.tryAcquire(NotificationJob.SCHEDULE_DISPATCH);
        assertThat(held).isPresent();
        try (JobLock.Lease ignored = held.get()) {
            assertThat(dispatcher.runOnce(at(now)).ran()).isFalse();
        }

        assertThat(status(ScheduleKind.APPEARANCE_STARTING, target, receiver)).isEqualTo("SCHEDULED");
    }

    // NT-SCH-14 (ND-142)

    @Test
    void 끝_상태이고_업무_기한_7일이_지난_행만_지우고_예약_행은_남긴다() {
        long receiver = member();
        Instant deadline = Instant.parse("2000-06-01T00:00:00Z");
        Instant sevenDaysLater = deadline.plusSeconds(7 * 86_400);
        long sent = NEXT_ID.incrementAndGet();
        long canceled = NEXT_ID.incrementAndGet();
        long scheduled = NEXT_ID.incrementAndGet();
        long recent = NEXT_ID.incrementAndGet();
        ScheduleKind kind = ScheduleKind.LECTURE_ORDER_ITEM_CONFIRMING;
        create(kind, sent, receiver, deadline.minusSeconds(60), deadline, Map.of("v", 1));
        jdbc.update("update notification.notification_schedule set status = 'SENT' where kind = ? and target_id = ?",
                kind.name(), sent);
        schedules.cancel(new ScheduleCommand.Cancel(kind, canceled, receiver, deadline), deadline);
        create(kind, scheduled, receiver, deadline.minusSeconds(60), deadline, Map.of("v", 1));
        schedules.cancel(new ScheduleCommand.Cancel(kind, recent, receiver, deadline.plusSeconds(1)), deadline);

        int deleted = dispatcher.cleanUp(sevenDaysLater);

        assertThat(deleted).isEqualTo(2);
        assertThat(rows(kind, sent, receiver)).isEmpty();
        assertThat(rows(kind, canceled, receiver)).isEmpty();
        assertThat(status(kind, scheduled, receiver)).isEqualTo("SCHEDULED");
        assertThat(status(kind, recent, receiver)).isEqualTo("CANCELED");
    }

    private void create(ScheduleKind kind, long target, long receiver, Instant sendAt, Instant deadline,
            Map<String, Object> payload) {
        jdbc.update("""
                insert into notification.notification_schedule
                    (kind, target_id, receiver_user_id, status, send_at, business_deadline, payload, created_at,
                     updated_at)
                values (?, ?, ?, 'SCHEDULED', ?, ?, ?::jsonb, now(), now())
                """, kind.name(), target, receiver, java.sql.Timestamp.from(sendAt),
                java.sql.Timestamp.from(deadline), toJson(payload));
    }

    private static String toJson(Map<String, Object> payload) {
        StringBuilder json = new StringBuilder("{");
        payload.forEach((key, value) -> json.append(json.length() > 1 ? "," : "").append('"').append(key)
                .append("\":").append(value instanceof Number ? value : "\"" + value + "\""));
        return json.append('}').toString();
    }

    /** 2001-01-11 09:00 KST(기한) */
    private static Map<String, Object> deadlinePayload(String title) {
        return Map.of("v", 1, "title", title, "deadline", "2001-01-11T09:00");
    }

    private void failSentUpdateOf(long target) {
        jdbc.execute("""
                create or replace function notification.test_fail_sent() returns trigger language plpgsql as $$
                begin
                    if new.status = 'SENT' and new.target_id = %d then
                        raise exception 'test: SENT 갱신 실패';
                    end if;
                    return new;
                end $$
                """.formatted(target));
        jdbc.execute("""
                create trigger test_fail_sent before update on notification.notification_schedule
                for each row execute function notification.test_fail_sent()
                """);
    }

    private void dropFailTrigger() {
        jdbc.execute("drop trigger if exists test_fail_sent on notification.notification_schedule");
        jdbc.execute("drop function if exists notification.test_fail_sent()");
    }

    private long scheduleId(ScheduleKind kind, long target, long receiver) {
        return jdbc.queryForObject("""
                select id from notification.notification_schedule
                where kind = ? and target_id = ? and receiver_user_id = ?
                """, Long.class, kind.name(), target, receiver);
    }

    private List<Map<String, Object>> rows(ScheduleKind kind, long target, long receiver) {
        return jdbc.queryForList("""
                select status from notification.notification_schedule
                where kind = ? and target_id = ? and receiver_user_id = ?
                """, kind.name(), target, receiver);
    }

    private String status(ScheduleKind kind, long target, long receiver) {
        return (String) rows(kind, target, receiver).getFirst().get("status");
    }

    private List<Map<String, Object>> alertsOf(long receiver) {
        return jdbc.queryForList("""
                select kind, source_event_type, source_target_id, target_type, target_id, message
                from notification.notification where receiver_user_id = ?
                """, receiver);
    }

    /** 늘 같은 시각을 주는 시계 */
    private static Clock at(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    /**
     * 처음 부를 때(회차의 예약 선택)는 {@code first}, 그 뒤(행 잠금 뒤 판정)는 {@code later}를 주는 시계. {@code runOnce}가 회차 시작에
     * 한 번 읽고 예약마다 잠근 뒤 다시 읽는 순서에 기대므로, 그 순서를 바꾸면 이 시계를 쓰는 시험도 함께 고친다
     */
    private static Clock stepping(Instant first, Instant later) {
        AtomicInteger calls = new AtomicInteger();
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return calls.getAndIncrement() == 0 ? first : later;
            }
        };
    }

    private Instant occurredAtOf(long receiver) {
        return jdbc.queryForObject("select occurred_at from notification.notification where receiver_user_id = ?",
                java.sql.Timestamp.class, receiver).toInstant();
    }

    private int failureCount(long scheduleId) {
        return jdbc.queryForObject("select failure_count from notification.notification_schedule where id = ?",
                Integer.class, scheduleId);
    }

    private long member() {
        long id = NEXT_ID.incrementAndGet();
        userSync.syncJoined(new UserDto(id, "회원" + id, Role.INFLUENCER, UserStatus.ACTIVE, 0L));
        return id;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("기다리다 시간이 지났다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
