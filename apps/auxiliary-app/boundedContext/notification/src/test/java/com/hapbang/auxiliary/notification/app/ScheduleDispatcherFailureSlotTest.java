package com.hapbang.auxiliary.notification.app;

import static org.assertj.core.api.Assertions.*;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.hapbang.auxiliary.notification.domain.ScheduleKind;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.IntegrationTest;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * L5-F 계산식의 전제(N15, NT-LOAD-L5F, ND-194). 보낼 시각이 같은 정상 990건과 실패 10건(받는 사람 복제본 없음 →
 * {@code RECEIVER_NOT_REPLICATED})을 배치 크기 100으로 매분 돌린다.
 * <ul>
 *     <li>보낼 예약은 {@code send_at, id} 순서로 고르고, 실패한 예약은 "예약"으로 남아 다음 회차에 다시 골라진다.</li>
 *     <li>그래서 실패 예약은 첫 시도 회차부터 3회차 동안 자리를 하나씩 차지하고 3번째 시도에서 건너뜀으로 끝난다(ND-147).</li>
 *     <li>정상 예약은 ⌈(990 + 3 × 10)/100⌉ = 11회차에 모두 끝나고, 10회차까지는 다 끝나지 않는다.</li>
 * </ul>
 * 기대값은 시험 명세의 식이다. 코드가 이 식과 다르면 기대값을 바꾸지 않고 보고한다.
 * <p>
 * 다른 시험 클래스의 예약과 섞이지 않게 2006년 시각을 쓴다({@link ScheduleDispatcherSelectionTest}가 2005년, 그 밖의 클래스는
 * 2001~2004년과 2026년 이후). 2006년에는 앞선 연도의 예약은 기한이 지나 고르지 않고, 뒤 연도의 예약은 보낼 시각 전이라 고르지 않는다.
 */
@IntegrationTest
class ScheduleDispatcherFailureSlotTest {

    private static final AtomicLong NEXT_ID = new AtomicLong(970_000_000L);
    private static final int NORMAL = 990;
    private static final int FAILING = 10;
    private static final Instant SEND_AT = Instant.parse("2006-03-01T00:00:00Z");

    @Autowired
    private ScheduleDispatcher dispatcher;

    @Autowired
    private NotificationUserSyncService userSync;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    @Test
    void 실패_예약이_3회차_동안_자리를_차지해도_정상_예약은_11회차_안에_끝나고_실패_예약은_3번째_시도에서_건너뜀으로_끝난다() {
        long receiver = member();
        List<Long> normalIds = new ArrayList<>();
        List<Long> failingIds = new ArrayList<>();
        // 실패 예약을 id 순서 곳곳에 흩는다(정상 99건마다 1건)
        for (int i = 0; i < NORMAL + FAILING; i++) {
            if (i % 100 == 50) {
                failingIds.add(create(NEXT_ID.incrementAndGet(), unknownReceiver()));
            } else {
                normalIds.add(create(NEXT_ID.incrementAndGet(), receiver));
            }
        }
        assertThat(normalIds).hasSize(NORMAL);
        assertThat(failingIds).hasSize(FAILING);
        double abandonedBefore = abandoned();

        Map<Long, Integer> firstAttemptRun = new HashMap<>();
        Map<Long, Integer> skippedRun = new HashMap<>();
        int normalDoneRun = -1;
        for (int run = 1; run <= 13; run++) {
            Instant at = SEND_AT.plusSeconds(35 + 60L * (run - 1));
            ScheduleDispatcher.RunResult result = dispatcher.runOnce(Clock.fixed(at, ZoneOffset.UTC));
            assertThat(result.ran()).isTrue();
            assertThat(result.sent() + result.failed()).as("회차 %d에 고른 수", run).isLessThanOrEqualTo(100);
            for (long id : failingIds) {
                int failures = failureCount(id);
                if (failures >= 1) {
                    firstAttemptRun.putIfAbsent(id, run - failures + 1);
                }
                if ("SKIPPED".equals(status(id))) {
                    skippedRun.putIfAbsent(id, run);
                }
            }
            if (normalDoneRun < 0 && sentCount(normalIds) == NORMAL) {
                normalDoneRun = run;
            }
        }

        // 정상 예약: ⌈(990 + 3 × 10)/100⌉ = 11회차
        assertThat(normalDoneRun).isEqualTo(11);
        // 실패 예약: 첫 시도 회차 + 2회차에 건너뜀, 실패 3번
        assertThat(skippedRun).hasSize(FAILING);
        for (long id : failingIds) {
            assertThat(skippedRun.get(id)).as("예약 %d", id).isEqualTo(firstAttemptRun.get(id) + 2);
            assertThat(failureCount(id)).isEqualTo(3);
        }
        assertThat(abandoned() - abandonedBefore).isEqualTo(FAILING);
    }

    private long create(long target, long receiver) {
        jdbc.update("""
                insert into notification.notification_schedule
                    (kind, target_id, receiver_user_id, status, send_at, business_deadline, payload, created_at,
                     updated_at)
                values (?, ?, ?, 'SCHEDULED', ?, ?, '{"v":1,"title":"합방","deadline":"2006-03-01T21:00"}'::jsonb,
                        now(), now())
                """, ScheduleKind.APPEARANCE_REQUEST_EXPIRING.name(), target, receiver, Timestamp.from(SEND_AT),
                Timestamp.from(SEND_AT.plusSeconds(12 * 3_600)));
        return jdbc.queryForObject("""
                select id from notification.notification_schedule where kind = ? and target_id = ?
                """, Long.class, ScheduleKind.APPEARANCE_REQUEST_EXPIRING.name(), target);
    }

    private String status(long id) {
        return jdbc.queryForObject("select status from notification.notification_schedule where id = ?", String.class,
                id);
    }

    private int failureCount(long id) {
        return jdbc.queryForObject("select failure_count from notification.notification_schedule where id = ?",
                Integer.class, id);
    }

    private int sentCount(List<Long> ids) {
        Long min = ids.getFirst();
        Long max = ids.getLast();
        return jdbc.queryForObject("""
                select count(*) from notification.notification_schedule
                where id between ? and ? and status = 'SENT'
                """, Integer.class, min, max);
    }

    private double abandoned() {
        Counter counter = meters.find("hapbang.notification.schedule.outcomes").tag("result", "abandoned").counter();
        return counter == null ? 0 : counter.count();
    }

    private long member() {
        long id = NEXT_ID.incrementAndGet();
        userSync.syncJoined(new UserDto(id, "회원" + id, Role.INFLUENCER, UserStatus.ACTIVE, 0L));
        return id;
    }

    /** 복제본을 만들지 않은 회원. 보낼 때 저장 서비스가 {@code RECEIVER_NOT_REPLICATED}를 던진다 */
    private static long unknownReceiver() {
        return NEXT_ID.incrementAndGet() + 500_000_000L;
    }
}
