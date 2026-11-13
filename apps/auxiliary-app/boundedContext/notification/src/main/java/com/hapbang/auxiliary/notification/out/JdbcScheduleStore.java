package com.hapbang.auxiliary.notification.out;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.notification.app.ScheduleCommand;
import com.hapbang.auxiliary.notification.app.ScheduleStore;
import com.hapbang.auxiliary.notification.domain.ScheduleKind;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 예약 기록을 {@code notification.notification_schedule}에 둔다(ND-025·ND-034).
 * <ul>
 *     <li>생성은 {@code ON CONFLICT DO NOTHING}이다. 취소가 먼저 남긴 "취소" 행이나 재전달로 이미 있는 행을 건드리지 않는다.</li>
 *     <li>취소는 {@code ON CONFLICT DO UPDATE ... WHERE status = 'SCHEDULED'}다. 행이 없으면 "취소" 행을 넣고, "예약"이면 바꾸고,
 *     끝 상태면 그대로 둔다.</li>
 *     <li>둘 다 한 문장이라 같은 예약의 생성·취소가 동시에 와도 PostgreSQL이 유일 키로 줄을 세운다. 먼저 커밋한 쪽이 넣고, 뒤의 문장은
 *     그 행에 충돌 처리를 한다. 그래서 순서와 관계없이 최종 상태는 취소다.</li>
 * </ul>
 */
@Component
class JdbcScheduleStore implements ScheduleStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;

    JdbcScheduleStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertIfAbsent(ScheduleCommand.Create command, Status status, Instant now) {
        LocalDateTime at = LocalDateTime.now();
        return jdbc.update("""
                insert into notification.notification_schedule
                    (kind, target_id, receiver_user_id, status, send_at, business_deadline, payload, processed_at,
                     created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                on conflict (kind, target_id, receiver_user_id) do nothing
                """, command.kind().name(), command.targetId(), command.receiverUserId(), status.name(),
                Timestamp.from(command.times().sendAt()), Timestamp.from(command.times().businessDeadline()),
                JSON.writeValueAsString(command.payload()),
                status == Status.SCHEDULED ? null : Timestamp.from(now), at, at) == 1;
    }

    @Override
    public boolean cancel(ScheduleCommand.Cancel command, Instant now) {
        LocalDateTime at = LocalDateTime.now();
        return jdbc.update("""
                insert into notification.notification_schedule
                    (kind, target_id, receiver_user_id, status, business_deadline, processed_at, created_at, updated_at)
                values (?, ?, ?, 'CANCELED', ?, ?, ?, ?)
                on conflict (kind, target_id, receiver_user_id) do update
                    set status = 'CANCELED', processed_at = excluded.processed_at, updated_at = excluded.updated_at
                    where notification_schedule.status = 'SCHEDULED'
                """, command.kind().name(), command.targetId(), command.receiverUserId(),
                Timestamp.from(command.businessDeadline()), Timestamp.from(now), at, at) == 1;
    }

    @Override
    public List<Long> findDueIds(Instant now, int limit) {
        return jdbc.queryForList("""
                select id from notification.notification_schedule
                where status = 'SCHEDULED' and send_at <= ? and business_deadline > ?
                order by send_at, id
                limit ?
                """, Long.class, Timestamp.from(now), Timestamp.from(now), limit);
    }

    /**
     * 기한이 지난 "예약"을 한 문장으로 건너뜀으로 끝낸다. 배치 항목마다 트랜잭션을 나누는 규칙({@code ConditionalOnSchedulingEnabled})의
     * 예외다: 알림 저장·외부 호출 없이 상태만 바꾸고, 보내거나 취소하는 중인(잠긴) 행은 {@code SKIP LOCKED}로 건너뛰며, 바깥 조건으로
     * 다시 "예약"인지 본다. 받는 사람 확인은 하지 않는다(어차피 보내지 않는다)
     */
    @Override
    public int skipExpired(Instant now, int limit) {
        return jdbc.update("""
                update notification.notification_schedule set status = 'SKIPPED', processed_at = ?, updated_at = ?
                where status = 'SCHEDULED' and id in (
                    select id from notification.notification_schedule
                    where status = 'SCHEDULED' and business_deadline <= ?
                    order by business_deadline, id
                    limit ?
                    for update skip locked)
                """, Timestamp.from(now), LocalDateTime.now(), Timestamp.from(now), limit);
    }

    @Override
    public Optional<Due> lockById(long id) {
        return jdbc.query("""
                select id, kind, target_id, receiver_user_id, status, send_at, business_deadline, payload
                from notification.notification_schedule where id = ? for update
                """, JdbcScheduleStore::due, id).stream().findFirst();
    }

    @Override
    public void finish(long id, Status status, Instant now) {
        int updated = jdbc.update("""
                update notification.notification_schedule set status = ?, processed_at = ?, updated_at = ?
                where id = ? and status = 'SCHEDULED'
                """, status.name(), Timestamp.from(now), LocalDateTime.now(), id);
        if (updated != 1) {
            throw new IllegalStateException("예약 상태를 바꾸지 못했다 id=" + id + ", status=" + status);
        }
    }

    @Override
    public OptionalInt recordFailure(long id, Instant now) {
        List<Integer> counts = jdbc.queryForList("""
                update notification.notification_schedule
                set failure_count = failure_count + 1, updated_at = ?
                where id = ? and status = 'SCHEDULED'
                returning failure_count
                """, Integer.class, LocalDateTime.now(), id);
        return counts.isEmpty() ? OptionalInt.empty() : OptionalInt.of(counts.getFirst());
    }

    @Override
    public int deleteFinished(Instant now, int retentionDays, int limit) {
        return jdbc.update("""
                delete from notification.notification_schedule
                where id in (
                    select id from notification.notification_schedule
                    where status <> 'SCHEDULED' and business_deadline <= ?
                    order by business_deadline, id
                    limit ?)
                """, Timestamp.from(now.minus(Duration.ofDays(retentionDays))), limit);
    }

    private static Due due(ResultSet rs, int rowNum) throws SQLException {
        String payload = rs.getString("payload");
        Timestamp sendAt = rs.getTimestamp("send_at");
        return new Due(rs.getLong("id"), ScheduleKind.valueOf(rs.getString("kind")), rs.getLong("target_id"),
                rs.getLong("receiver_user_id"), Status.valueOf(rs.getString("status")),
                sendAt == null ? null : sendAt.toInstant(), rs.getTimestamp("business_deadline").toInstant(),
                payload == null ? null : JSON.readValue(payload, MAP));
    }
}
