package com.hapbang.auxiliary.notification.app;

import static com.hapbang.auxiliary.notification.support.NotificationFixtures.draft;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.auxiliary.notification.domain.NotificationDraft;
import com.hapbang.auxiliary.notification.support.TestNotificationTemplate;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.IntegrationTest;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 저장 결과 집계(ND-160)를 실제 트랜잭션 매니저와 저장 서비스로 본다. test-spec NT-OBS-04(일부).
 * 저장 서비스가 집계를 commit 뒤로 걸어 둔 다음 바깥 트랜잭션이 롤백되면, 걸어 둔 집계는 버려지고 알림도 남지 않는다.
 * 같은 초안을 다시 저장해 commit되면 그때 한 번 센다.
 */
@IntegrationTest
@Import(TestNotificationTemplate.Config.class)
class NotificationOutcomeRecorderIntegrationTest {

    private static final AtomicLong NEXT_ID = new AtomicLong(944_900);
    private static final Instant T0 = Instant.parse("2026-11-14T00:00:00Z");
    private static final String TOPIC = "outcome-test-" + UUID.randomUUID();

    @Autowired
    private NotificationWriter writer;

    @Autowired
    private NotificationAlertService alerts;

    @Autowired
    private NotificationOutcomeRecorder recorder;

    @Autowired
    private NotificationUserSyncService userSync;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @AfterEach
    void tearDown() {
        recorder.exit();
    }

    @Test
    void 집계를_걸어_둔_뒤_바깥_트랜잭션이_롤백되면_세지_않고_다시_commit되면_한_번_센다() {
        long receiver = NEXT_ID.incrementAndGet();
        userSync.syncJoined(new UserDto(receiver, "회원", Role.USER, UserStatus.ACTIVE, 0L));
        recorder.enter(TOPIC);

        transactionTemplate.executeWithoutResult(status -> {
            assertThat(writer.save(draft(receiver, "o-1", 7L, "호스트", T0)).outcome())
                    .isEqualTo(NotificationWriteResult.Outcome.CREATED);
            status.setRollbackOnly();
        });

        assertThat(created()).isZero();
        assertThat(rows(receiver)).isZero();

        transactionTemplate.executeWithoutResult(status -> writer.save(draft(receiver, "o-1", 7L, "호스트", T0)));

        assertThat(created()).isEqualTo(1.0);
        assertThat(rows(receiver)).isEqualTo(1);
    }

    /** 저장 서비스 전에 받는 사람 사전 판단으로 건너뛴 것도 SKIPPED_RECEIVER로 센다(ND-164). 저장 서비스와 겹쳐 두 번 세지 않는다 */
    @Test
    void 받는_사람_사전_판단으로_건너뛰어도_commit_뒤에_한_번만_세고_롤백되면_세지_않는다() {
        long receiver = NEXT_ID.incrementAndGet();
        long mentionedWithoutReplica = NEXT_ID.incrementAndGet();
        userSync.syncJoined(new UserDto(receiver, "탈퇴회원", Role.USER, UserStatus.WITHDRAWN, 0L));
        NotificationDraft checked = draft(receiver, "pre-1", mentionedWithoutReplica, "상대", Instant.now());
        recorder.enter(TOPIC);

        transactionTemplate.executeWithoutResult(status -> {
            alerts.createMentioning(checked, mentionedWithoutReplica, nickname -> checked);
            status.setRollbackOnly();
        });

        assertThat(skipped()).isZero();

        transactionTemplate.executeWithoutResult(
                status -> alerts.createMentioning(checked, mentionedWithoutReplica, nickname -> checked));

        assertThat(skipped()).isEqualTo(1.0);
        assertThat(rows(receiver)).isZero();
    }

    @Test
    void 소비_문맥_밖의_사전_판단은_세지_않는다() {
        long receiver = NEXT_ID.incrementAndGet();
        userSync.syncJoined(new UserDto(receiver, "정지회원", Role.USER, UserStatus.SUSPENDED, 0L));
        NotificationDraft checked = draft(receiver, "pre-2", 7L, "상대", Instant.now());
        double before = totalSkipped();

        alerts.createMentioning(checked, 7L, nickname -> checked);

        assertThat(totalSkipped()).isEqualTo(before);
        assertThat(rows(receiver)).isZero();
    }

    private double skipped() {
        Counter counter = meterRegistry.find(NotificationOutcomeRecorder.OUTCOMES_METRIC).tag("topic", TOPIC)
                .tag("result", "skipped_receiver").counter();
        return counter == null ? 0 : counter.count();
    }

    private double totalSkipped() {
        return meterRegistry.find(NotificationOutcomeRecorder.OUTCOMES_METRIC).tag("result", "skipped_receiver")
                .counters().stream().mapToDouble(Counter::count).sum();
    }

    private double created() {
        Counter counter = meterRegistry.find(NotificationOutcomeRecorder.OUTCOMES_METRIC).tag("topic", TOPIC)
                .tag("result", "created").counter();
        return counter == null ? 0 : counter.count();
    }

    private int rows(long receiver) {
        return jdbcTemplate.queryForObject("select count(*) from notification.notification where receiver_user_id = ?",
                Integer.class, receiver);
    }
}
