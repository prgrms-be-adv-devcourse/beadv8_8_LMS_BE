package com.hapbang.auxiliary.notification.app;

import static com.hapbang.auxiliary.notification.support.NotificationFixtures.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.hapbang.auxiliary.notification.app.NotificationWriteResult.Outcome;
import com.hapbang.auxiliary.notification.app.exception.NotificationErrorCode;
import com.hapbang.auxiliary.notification.app.exception.NotificationException;
import com.hapbang.auxiliary.notification.domain.Notification;
import com.hapbang.auxiliary.notification.out.NotificationRepository;
import com.hapbang.auxiliary.notification.support.TestNotificationTemplate;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 알림 저장 서비스(ND-018·019·020·022·035·053). test-spec NT-IDEM-01·02, NT-USR-03·04, NT-RET-01~05, NT-GEN-13.
 */
@IntegrationTest
@Import(TestNotificationTemplate.Config.class)
class NotificationWriterTest {

    private static final AtomicLong NEXT_ID = new AtomicLong(10_000);
    private static final Instant T0 = Instant.parse("2026-11-10T00:00:00Z");

    @Autowired
    private NotificationWriter writer;

    @Autowired
    private NotificationRepository repository;

    @Autowired
    private NotificationUserSyncService userSync;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 받는_사람에게_알림을_만들고_값과_언급_회원과_문구를_저장한다() {
        long receiver = member(UserStatus.ACTIVE);

        NotificationWriteResult result = writer.save(draft(receiver, "s-1", 7L, "호스트", T0));

        assertThat(result.outcome()).isEqualTo(Outcome.CREATED);
        Notification saved = repository.findById(result.notificationId()).orElseThrow();
        assertThat(saved.getReceiverUserId()).isEqualTo(receiver);
        assertThat(saved.getKind()).isEqualTo(TestNotificationTemplate.KIND);
        assertThat(saved.getMessage()).isEqualTo("호스트님이 강의 s-1에 반응했습니다.");
        assertThat(saved.getMentionedUserIds()).containsExactly(7L);
        assertThat(saved.getPayload()).containsEntry("v", 1);
        assertThat(saved.getOccurredAt()).isEqualTo(T0);
        assertThat(saved.getReadAt()).isNull();
        // NT-GEN-13: 저장된 값만으로 같은 문구를 다시 만든다
        assertThat(new TestNotificationTemplate().render(saved.getPayload())).isEqualTo(saved.getMessage());
    }

    @Test
    void 같은_이벤트를_다시_받으면_예외_없이_중복으로_보고_한_건만_남긴다() {
        long receiver = member(UserStatus.ACTIVE);

        writer.save(draft(receiver, "s-1", 7L, "호스트", T0));
        NotificationWriteResult again = writer.save(draft(receiver, "s-1", 7L, "호스트", T0));

        assertThat(again.outcome()).isEqualTo(Outcome.DUPLICATE);
        assertThat(rowsOf(receiver)).isEqualTo(1);
    }

    @Test
    void 같은_이벤트를_동시에_저장해도_한_건이고_모두_예외_없이_끝난다() throws Exception {
        long receiver = member(UserStatus.ACTIVE);

        List<NotificationWriteResult> results = runTogether(List.of(
                () -> writer.save(draft(receiver, "s-1", 7L, "호스트", T0)),
                () -> writer.save(draft(receiver, "s-1", 7L, "호스트", T0)),
                () -> writer.save(draft(receiver, "s-1", 7L, "호스트", T0))));

        assertThat(results).extracting(NotificationWriteResult::outcome)
                .containsExactlyInAnyOrder(Outcome.CREATED, Outcome.DUPLICATE, Outcome.DUPLICATE);
        assertThat(rowsOf(receiver)).isEqualTo(1);
    }

    @Test
    void 탈퇴한_받는_사람에게는_만들지_않는다() {
        long withdrawn = member(UserStatus.WITHDRAWN);

        assertThat(writer.save(draft(withdrawn, "s-1", 7L, "호스트", T0)).outcome())
                .isEqualTo(Outcome.SKIPPED_WITHDRAWN);
        assertThat(rowsOf(withdrawn)).isZero();
    }

    @Test
    void 받는_사람_복제본이_없으면_만들지_않고_실패한다() {
        long unknown = NEXT_ID.incrementAndGet();

        assertThatThrownBy(() -> writer.save(draft(unknown, "s-1", 7L, "호스트", T0)))
                .isInstanceOfSatisfying(NotificationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(NotificationErrorCode.RECEIVER_NOT_REPLICATED));
        assertThat(rowsOf(unknown)).isZero();
    }

    @Test
    void 스물한번째를_저장하면_같은_트랜잭션에서_가장_오래된_알림을_Soft_Delete하고_행은_남긴다() {
        long receiver = member(UserStatus.ACTIVE);
        saveMany(receiver, 20);

        writer.save(draft(receiver, "s-new", 7L, "호스트", T0.plusSeconds(100)));

        assertThat(aliveSources(receiver)).hasSize(20).doesNotContain("s-0").contains("s-new");
        assertThat(rowsOf(receiver)).isEqualTo(21);
        assertThat(deletedAtOf(receiver, "s-0")).isNotNull();
    }

    @Test
    void 밀려나_Soft_Delete된_알림의_이벤트를_다시_받아도_되살리지_않고_새로_만들지도_않는다() {
        long receiver = member(UserStatus.ACTIVE);
        saveMany(receiver, 21);
        Object deletedAt = deletedAtOf(receiver, "s-0");
        assertThat(deletedAt).isNotNull();

        NotificationWriteResult again = writer.save(draft(receiver, "s-0", 7L, "호스트", T0));

        assertThat(again.outcome()).isEqualTo(Outcome.DUPLICATE);
        assertThat(rowsOf(receiver)).isEqualTo(21);
        assertThat(deletedAtOf(receiver, "s-0")).isEqualTo(deletedAt);
        assertThat(aliveSources(receiver)).hasSize(20).doesNotContain("s-0");
    }

    @Test
    void 늦게_도착한_오래된_알림은_저장과_같은_트랜잭션에서_자신이_Soft_Delete된다() {
        long receiver = member(UserStatus.ACTIVE);
        saveMany(receiver, 20);

        writer.save(draft(receiver, "s-old", 7L, "호스트", T0.minusSeconds(3600)));

        assertThat(aliveSources(receiver)).hasSize(20).doesNotContain("s-old");
        assertThat(deletedAtOf(receiver, "s-old")).isNotNull();
    }

    @Test
    void 같은_회원에게_서로_다른_알림이_동시에_저장돼도_삭제되지_않은_알림은_정확히_20개다() throws Exception {
        long receiver = member(UserStatus.ACTIVE);
        long other = member(UserStatus.ACTIVE);
        saveMany(receiver, 20);
        List<Callable<NotificationWriteResult>> calls = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String source = "c-" + i;
            Instant at = T0.plusSeconds(1000 + i);
            calls.add(() -> writer.save(draft(receiver, source, 7L, "호스트", at)));
        }
        // 다른 회원의 저장은 이 회원의 락을 기다리지 않는다
        calls.add(() -> writer.save(draft(other, "o-1", 7L, "호스트", T0)));

        List<NotificationWriteResult> results = runTogether(calls);

        assertThat(results).extracting(NotificationWriteResult::outcome).containsOnly(Outcome.CREATED);
        assertThat(aliveSources(receiver)).hasSize(20).contains("c-0", "c-1", "c-2", "c-3", "c-4", "c-5");
        assertThat(rowsOf(receiver)).isEqualTo(26);
        assertThat(aliveSources(other)).containsExactly("o-1");
    }

    @Test
    void 보존_정리는_행을_지우지_않는다() {
        long receiver = member(UserStatus.ACTIVE);
        saveMany(receiver, 25);

        assertThat(rowsOf(receiver)).isEqualTo(25);
        assertThat(aliveSources(receiver)).hasSize(20);
    }

    // ---------------------------------------------------------------------------------------------

    private long member(UserStatus status) {
        long id = NEXT_ID.incrementAndGet();
        userSync.syncJoined(new UserDto(id, "회원" + id, Role.USER, status, 0L));
        return id;
    }

    /** s-0(가장 오래됨)부터 s-(n-1)까지 1초 간격으로 저장한다 */
    private void saveMany(long receiver, int count) {
        for (int i = 0; i < count; i++) {
            writer.save(draft(receiver, "s-" + i, 7L, "호스트", T0.plusSeconds(i)));
        }
    }

    private int rowsOf(long receiver) {
        return jdbcTemplate.queryForObject(
                "select count(*) from notification.notification where receiver_user_id = ?", Integer.class, receiver);
    }

    private List<String> aliveSources(long receiver) {
        return jdbcTemplate.queryForList("select source_target_id from notification.notification "
                + "where receiver_user_id = ? and deleted_at is null", String.class, receiver);
    }

    private Object deletedAtOf(long receiver, String source) {
        return jdbcTemplate.queryForObject("select deleted_at from notification.notification "
                + "where receiver_user_id = ? and source_target_id = ?", Object.class, receiver, source);
    }

    private static List<NotificationWriteResult> runTogether(List<Callable<NotificationWriteResult>> calls)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<NotificationWriteResult>> futures = new ArrayList<>();
            for (Callable<NotificationWriteResult> call : calls) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<NotificationWriteResult> results = new ArrayList<>();
            for (Future<NotificationWriteResult> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}
