package com.hapbang.auxiliary.notification.app;

import static org.assertj.core.api.Assertions.*;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.hapbang.auxiliary.notification.domain.ScheduleKind;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 예약 배치가 한 회차에 고르는 예약(#929 리뷰 2·4, ND-029·ND-135·ND-144). {@link ScheduleDispatcher#runOnce} 경로로 본다.
 * 한 회차에 1건만 고르게 해, 기한이 지난 예약이 앞에 있어도 유효한 예약이 밀리지 않는지 본다.
 * <p>
 * 다른 시험 클래스의 예약과 섞이지 않게 2005년 시각을 쓴다. 같은 DB를 쓰는 다른 클래스의 과거 시각 예약(2001~2004년)은 이 시각에
 * 모두 업무 기한이 지나 있어 회차가 고르지 않으므로(먼저 건너뜀으로 끝나거나 남는다) 결과가 정해진다. 시각 창을 옮길 때는 이 전제를 다시
 * 확인한다. 기한 지난 예약 자신은 다른 클래스의 기한 지난 행이 먼저 끝날 수 있어 "보내지 않음"만 확인한다.
 */
@IntegrationTest
@TestPropertySource(properties = "hapbang.notification.schedule.batch-size=1")
class ScheduleDispatcherSelectionTest {

    private static final AtomicLong NEXT_ID = new AtomicLong(928_900);

    @Autowired
    private ScheduleDispatcher dispatcher;

    @Autowired
    private NotificationUserSyncService userSync;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void 보낼_시각이_지난_예약은_보내고_아직_아닌_예약은_예약으로_남긴다() {
        long receiver = member();
        long due = NEXT_ID.incrementAndGet();
        long later = NEXT_ID.incrementAndGet();
        Instant now = Instant.parse("2005-01-01T00:00:00Z");
        create(ScheduleKind.APPEARANCE_STARTING, due, receiver, now.minusSeconds(1), now.plusSeconds(86_400));
        create(ScheduleKind.APPEARANCE_STARTING, later, receiver, now.plusSeconds(1), now.plusSeconds(86_400));

        dispatcher.runOnce(Clock.fixed(now, ZoneOffset.UTC));
        dispatcher.runOnce(Clock.fixed(now, ZoneOffset.UTC));

        assertThat(status(due, receiver)).isEqualTo("SENT");
        assertThat(status(later, receiver)).isEqualTo("SCHEDULED");
    }

    @Test
    void 기한이_지난_예약이_보낼_시각_순으로_앞에_있어도_유효한_예약을_고른다() {
        long receiver = member();
        long expired = NEXT_ID.incrementAndGet();
        long valid = NEXT_ID.incrementAndGet();
        Instant now = Instant.parse("2005-02-01T00:00:00Z");
        // 보낼 시각은 기한 지난 예약이 더 이르다. 한 회차에 1건만 고른다
        create(ScheduleKind.APPEARANCE_REQUEST_EXPIRING, expired, receiver, now.minusSeconds(7_200),
                now.minusSeconds(3_600));
        create(ScheduleKind.APPEARANCE_REQUEST_EXPIRING, valid, receiver, now.minusSeconds(60),
                now.plusSeconds(60));

        dispatcher.runOnce(Clock.fixed(now, ZoneOffset.UTC));

        assertThat(status(valid, receiver)).isEqualTo("SENT");
        assertThat(status(expired, receiver)).isNotEqualTo("SENT");
        assertThat(jdbc.queryForObject("""
                select count(*) from notification.notification
                where receiver_user_id = ? and source_target_id = ?
                """, Long.class, receiver, String.valueOf(expired))).isZero();
    }

    private void create(ScheduleKind kind, long target, long receiver, Instant sendAt, Instant deadline) {
        jdbc.update("""
                insert into notification.notification_schedule
                    (kind, target_id, receiver_user_id, status, send_at, business_deadline, payload, created_at,
                     updated_at)
                values (?, ?, ?, 'SCHEDULED', ?, ?, '{"v":1,"title":"합방","deadline":"2005-01-02T09:00"}'::jsonb,
                        now(), now())
                """, kind.name(), target, receiver, Timestamp.from(sendAt), Timestamp.from(deadline));
    }

    private String status(long target, long receiver) {
        return jdbc.queryForObject("""
                select status from notification.notification_schedule where target_id = ? and receiver_user_id = ?
                """, String.class, target, receiver);
    }

    private long member() {
        long id = NEXT_ID.incrementAndGet();
        userSync.syncJoined(new UserDto(id, "회원" + id, Role.INFLUENCER, UserStatus.ACTIVE, 0L));
        return id;
    }
}
