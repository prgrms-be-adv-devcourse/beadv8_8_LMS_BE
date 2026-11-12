package com.hapbang.auxiliary.notification.app;

import static com.hapbang.auxiliary.notification.support.NotificationFixtures.*;
import static org.assertj.core.api.Assertions.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.auxiliary.notification.app.NotificationWriteResult.Outcome;
import com.hapbang.auxiliary.notification.app.exception.NotificationErrorCode;
import com.hapbang.auxiliary.notification.app.exception.NotificationException;
import com.hapbang.auxiliary.notification.out.NotificationRepository;
import com.hapbang.auxiliary.notification.support.TestNotificationTemplate;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 강의 지급 하루 묶음 저장(ND-032·ND-097·ND-101·ND-102·ND-103). test-spec NT-BUN-01~08.
 */
@IntegrationTest
@Import(TestNotificationTemplate.Config.class)
class NotificationDailyPayoutTest {

    /** 다른 테스트 클래스와 겹치지 않는 회원·지급 ID 범위(같은 DB를 함께 쓴다) */
    private static final AtomicLong NEXT_ID = new AtomicLong(80_000);
    private static final String KIND = "PAYOUT_COMPLETED_DAILY";
    /** KST 2026-11-06 10:00 */
    private static final Instant DAY = Instant.parse("2026-11-06T01:00:00Z");

    @Autowired
    private NotificationWriter writer;

    @Autowired
    private NotificationUserSyncService userSync;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private NotificationRepository repository;

    @Autowired
    private TransactionTemplate transaction;

    // NT-BUN-01

    @Test
    void 판매자와_날짜마다_묶음_한_건에_금액과_건수를_더한다() {
        long s = member(UserStatus.ACTIVE);
        long t = member(UserStatus.ACTIVE);

        assertThat(writer.addDailyPayout(payout(s, 1_000L, DAY)).outcome()).isEqualTo(Outcome.CREATED);
        assertThat(writer.addDailyPayout(payout(s, 2_000L, DAY.plusSeconds(60))).outcome())
                .isEqualTo(Outcome.UPDATED);
        writer.addDailyPayout(payout(s, 3_000L, DAY.plusSeconds(120)));
        writer.addDailyPayout(payout(t, 9_000L, DAY));

        Map<String, Object> bundle = singleBundle(s);
        assertThat(bundle).containsEntry("kind", KIND).containsEntry("bundle_key", "2026-11-06")
                .containsEntry("target_type", null).containsEntry("target_id", null)
                .containsEntry("source_target_id", null);
        assertThat(bundle.get("message")).isEqualTo("11월 6일 강의 판매 수익 6,000 HP가 지급되었습니다. (3건)");
        assertThat(payloadNumber(s, "totalPaidAmountHp")).isEqualTo(6_000L);
        assertThat(payloadNumber(s, "count")).isEqualTo(3L);
        assertThat(singleBundle(t).get("message")).isEqualTo("11월 6일 강의 판매 수익 9,000 HP가 지급되었습니다. (1건)");
        assertThat(itemsOf(s)).isEqualTo(3);
    }

    // NT-BUN-02 (순차)

    @Test
    void 같은_지급을_다시_받으면_중복으로_끝나고_금액과_건수와_읽음을_바꾸지_않는다() {
        long s = member(UserStatus.ACTIVE);
        DailyPayout first = payout(s, 1_000L, DAY);
        writer.addDailyPayout(first);
        markRead(s);

        NotificationWriteResult again = writer.addDailyPayout(first);

        assertThat(again.outcome()).isEqualTo(Outcome.DUPLICATE);
        assertThat(payloadNumber(s, "count")).isEqualTo(1L);
        assertThat(payloadNumber(s, "totalPaidAmountHp")).isEqualTo(1_000L);
        assertThat(singleBundle(s).get("read_at")).isNotNull();
        assertThat(itemsOf(s)).isEqualTo(1);
    }

    // NT-BUN-03 (통합)

    @Test
    void KST_자정을_넘긴_지급은_다른_날짜_묶음이다() {
        long s = member(UserStatus.ACTIVE);

        writer.addDailyPayout(payout(s, 1_000L, Instant.parse("2026-11-06T14:59:59Z")));
        writer.addDailyPayout(payout(s, 2_000L, Instant.parse("2026-11-06T15:00:00Z")));

        assertThat(jdbc.queryForList("select bundle_key from notification.notification where receiver_user_id = ? "
                + "order by bundle_key", String.class, s)).containsExactly("2026-11-06", "2026-11-07");
    }

    // NT-BUN-04

    @Test
    void 갱신하면_다시_안_읽음이고_발생_시각은_기존_값과_새_paidAt_중_늦은_값이다() {
        long s = member(UserStatus.ACTIVE);
        writer.addDailyPayout(payout(s, 1_000L, DAY.plusSeconds(600)));
        markRead(s);

        writer.addDailyPayout(payout(s, 2_000L, DAY.plusSeconds(1_200)));
        assertThat(singleBundle(s).get("read_at")).isNull();
        assertThat(occurredAt(s)).isEqualTo(DAY.plusSeconds(1_200));

        // 늦게 도착한 같은 날 이른 지급: 금액은 더하고 발생 시각은 뒤로 가지 않는다
        writer.addDailyPayout(payout(s, 3_000L, DAY));
        assertThat(occurredAt(s)).isEqualTo(DAY.plusSeconds(1_200));
        assertThat(payloadNumber(s, "totalPaidAmountHp")).isEqualTo(6_000L);
    }

    // NT-BUN-05

    @Test
    void 밀려난_묶음에_새_지급이_오면_되살리고_같은_트랜잭션에서_20개를_지킨다() {
        long s = member(UserStatus.ACTIVE);
        DailyPayout first = payout(s, 1_000L, DAY);
        writer.addDailyPayout(first);
        markRead(s);
        // 묶음보다 늦은 알림 20개로 묶음을 밀어낸다
        for (int i = 0; i < 20; i++) {
            writer.save(draft(s, "n-" + i, 7L, "호스트", DAY.plusSeconds(10 + i)));
        }
        assertThat(singleBundle(s).get("deleted_at")).isNotNull();

        // 같은 지급의 재전달은 되살리지 않는다
        assertThat(writer.addDailyPayout(first).outcome()).isEqualTo(Outcome.DUPLICATE);
        assertThat(singleBundle(s).get("deleted_at")).isNotNull();
        assertThat(singleBundle(s).get("read_at")).isNotNull();

        // 새 지급은 되살린다
        NotificationWriteResult revived = writer.addDailyPayout(payout(s, 2_000L, DAY.plusSeconds(3_600)));

        assertThat(revived.outcome()).isEqualTo(Outcome.UPDATED);
        Map<String, Object> bundle = singleBundle(s);
        assertThat(bundle.get("deleted_at")).isNull();
        assertThat(bundle.get("read_at")).isNull();
        assertThat(occurredAt(s)).isEqualTo(DAY.plusSeconds(3_600));
        assertThat(payloadNumber(s, "totalPaidAmountHp")).isEqualTo(3_000L);
        assertThat(payloadNumber(s, "count")).isEqualTo(2L);
        assertThat(aliveOf(s)).isEqualTo(NotificationWriter.KEEP);
        // 되살린 대신 가장 오래된 다른 알림이 밀려났다
        assertThat(jdbc.queryForObject("select deleted_at from notification.notification "
                + "where receiver_user_id = ? and source_target_id = 'n-0'", Object.class, s)).isNotNull();

        // 되살린 뒤 같은 지급이 다시 와도 묶음은 그대로다
        markRead(s);
        assertThat(writer.addDailyPayout(first).outcome()).isEqualTo(Outcome.DUPLICATE);
        assertThat(singleBundle(s).get("read_at")).isNotNull();
        assertThat(payloadNumber(s, "count")).isEqualTo(2L);
        assertThat(occurredAt(s)).isEqualTo(DAY.plusSeconds(3_600));
    }

    @Test
    void 되살린_묶음이_21번째보다_오래되면_같은_트랜잭션에서_다시_숨는다() {
        long s = member(UserStatus.ACTIVE);
        writer.addDailyPayout(payout(s, 1_000L, DAY));
        for (int i = 0; i < 20; i++) {
            writer.save(draft(s, "n-" + i, 7L, "호스트", DAY.plusSeconds(86_400 + i)));
        }

        writer.addDailyPayout(payout(s, 2_000L, DAY.plusSeconds(60)));

        assertThat(singleBundle(s).get("deleted_at")).isNotNull();
        assertThat(payloadNumber(s, "count")).isEqualTo(2L);
        assertThat(aliveOf(s)).isEqualTo(NotificationWriter.KEEP);
    }

    // ND-103 (3): 한 트랜잭션

    @Test
    void 지급_항목_기록이_실패하면_묶음_갱신도_되돌린다() {
        long s = member(UserStatus.ACTIVE);
        writer.addDailyPayout(payout(s, 1_000L, DAY));
        markRead(s);

        // 지급 HP가 음수인 항목은 표 제약(CHECK)에 걸린다. 묶음을 고친 뒤 항목 기록에서 실패한다
        assertThatThrownBy(() -> writer.addDailyPayout(payout(s, -1L, DAY.plusSeconds(60))))
                .isInstanceOf(RuntimeException.class);

        assertThat(payloadNumber(s, "count")).isEqualTo(1L);
        assertThat(payloadNumber(s, "totalPaidAmountHp")).isEqualTo(1_000L);
        assertThat(singleBundle(s).get("read_at")).isNotNull();
        assertThat(occurredAt(s)).isEqualTo(DAY);
        assertThat(itemsOf(s)).isEqualTo(1);
    }

    // 받는 사람(ND-018·ND-035·ND-072)

    @Test
    void 탈퇴한_판매자에게는_만들지_않고_지급_항목도_남기지_않는다() {
        long withdrawn = member(UserStatus.WITHDRAWN);

        assertThat(writer.addDailyPayout(payout(withdrawn, 1_000L, DAY)).outcome())
                .isEqualTo(Outcome.SKIPPED_RECEIVER);
        assertThat(bundlesOf(withdrawn)).isEmpty();
        assertThat(itemsOf(withdrawn)).isZero();
    }

    @Test
    void 판매자_복제본이_없으면_만들지_않고_실패한다() {
        long unknown = NEXT_ID.incrementAndGet();

        assertThatThrownBy(() -> writer.addDailyPayout(payout(unknown, 1_000L, DAY)))
                .isInstanceOfSatisfying(NotificationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(NotificationErrorCode.RECEIVER_NOT_REPLICATED));
        assertThat(itemsOf(unknown)).isZero();
    }

    // NT-BUN-02 (동시)

    @Test
    void 같은_지급이_동시에_와도_한_번만_더한다() throws Exception {
        long s = member(UserStatus.ACTIVE);
        DailyPayout same = payout(s, 1_000L, DAY);

        List<NotificationWriteResult> results = runTogether(List.of(() -> writer.addDailyPayout(same),
                () -> writer.addDailyPayout(same), () -> writer.addDailyPayout(same)));

        assertThat(results).extracting(NotificationWriteResult::outcome)
                .containsExactlyInAnyOrder(Outcome.CREATED, Outcome.DUPLICATE, Outcome.DUPLICATE);
        assertThat(payloadNumber(s, "count")).isEqualTo(1L);
        assertThat(itemsOf(s)).isEqualTo(1);
    }

    // NT-BUN-06

    @Test
    void 서로_다른_지급이_동시에_와도_지급마다_정확히_한_번씩_더한다() throws Exception {
        long s = member(UserStatus.ACTIVE);
        List<Callable<NotificationWriteResult>> calls = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            DailyPayout payout = payout(s, i * 100L, DAY.plusSeconds(i));
            calls.add(() -> writer.addDailyPayout(payout));
        }

        runTogether(calls);

        assertThat(bundlesOf(s)).hasSize(1);
        assertThat(payloadNumber(s, "count")).isEqualTo(8L);
        assertThat(payloadNumber(s, "totalPaidAmountHp")).isEqualTo(3_600L);
        assertThat(itemsOf(s)).isEqualTo(8);
    }

    // NT-BUN-07

    @Test
    void 되살림과_새_알림이_동시에_저장돼도_보이는_알림은_20개를_넘지_않고_둘_다_반영된다() throws Exception {
        long s = member(UserStatus.ACTIVE);
        writer.addDailyPayout(payout(s, 1_000L, DAY));
        for (int i = 0; i < 20; i++) {
            writer.save(draft(s, "n-" + i, 7L, "호스트", DAY.plusSeconds(10 + i)));
        }

        runTogether(List.of(() -> writer.addDailyPayout(payout(s, 2_000L, DAY.plusSeconds(3_600))),
                () -> writer.save(draft(s, "late", 7L, "호스트", DAY.plusSeconds(3_000)))));

        assertThat(aliveOf(s)).isEqualTo(NotificationWriter.KEEP);
        assertThat(singleBundle(s).get("deleted_at")).isNull();
        assertThat(payloadNumber(s, "count")).isEqualTo(2L);
        assertThat(jdbc.queryForObject("select deleted_at from notification.notification "
                + "where receiver_user_id = ? and source_target_id = 'late'", Object.class, s)).isNull();
    }

    // NT-BUN-08

    @Test
    void 되살림과_재전달이_겹쳐도_새_지급만_한_번_반영된다() throws Exception {
        long s = member(UserStatus.ACTIVE);
        DailyPayout first = payout(s, 1_000L, DAY);
        writer.addDailyPayout(first);
        for (int i = 0; i < 20; i++) {
            writer.save(draft(s, "n-" + i, 7L, "호스트", DAY.plusSeconds(10 + i)));
        }

        // 처음 보는 같은 지급 둘(되살림 경합)과 이미 더한 지급의 재전달을 함께 보낸다
        DailyPayout fresh = payout(s, 2_000L, DAY.plusSeconds(3_600));
        List<NotificationWriteResult> results = runTogether(List.of(() -> writer.addDailyPayout(fresh),
                () -> writer.addDailyPayout(fresh), () -> writer.addDailyPayout(first)));

        assertThat(results).extracting(NotificationWriteResult::outcome)
                .containsExactlyInAnyOrder(Outcome.UPDATED, Outcome.DUPLICATE, Outcome.DUPLICATE);
        assertThat(singleBundle(s).get("deleted_at")).isNull();
        assertThat(payloadNumber(s, "count")).isEqualTo(2L);
        assertThat(payloadNumber(s, "totalPaidAmountHp")).isEqualTo(3_000L);
        assertThat(itemsOf(s)).isEqualTo(2);
    }

    // NT-BUN-09: 전체 읽음(받는 사람 행 잠금 없이 안 읽은 행을 ID 오름차순으로 잠근다)과 겹침. 회귀 방지용이다: 묶음 행만 먼저
    // 잠그던 이전 코드도 이 두 경우에는 교착에 이르지 않아(보이는 묶음 갱신은 밀어낼 행이 없고, 되살림 때 묶음은 전체 읽음의 잠금 대상이
    // 아니다) 이전 코드에서도 통과한다. 알림 행 잠금이 ID 오름차순이 아니게 바뀌면 교착·시간 초과로 실패할 수 있다

    @Test
    void 보이는_묶음_갱신과_전체_읽음이_겹쳐도_교착_없이_둘_다_반영된다() throws Exception {
        for (int round = 0; round < 5; round++) {
            long s = member(UserStatus.ACTIVE);
            // 묶음이 가장 오래되도록 먼저 만들고, 그 뒤 알림 19개(보이는 알림 20개, 모두 안 읽음)
            writer.addDailyPayout(payout(s, 1_000L, DAY));
            for (int i = 0; i < 19; i++) {
                writer.save(draft(s, "n-" + i, 7L, "호스트", DAY.plusSeconds(10 + i)));
            }

            runTogether(List.of(() -> writer.addDailyPayout(payout(s, 2_000L, DAY.plusSeconds(60))),
                    () -> markAllRead(s)));

            assertThat(payloadNumber(s, "count")).isEqualTo(2L);
            assertThat(aliveOf(s)).isEqualTo(NotificationWriter.KEEP);
            assertThat(unreadOthersOf(s)).isZero();
        }
    }

    @Test
    void 밀려난_묶음_되살림과_전체_읽음이_겹쳐도_교착_없이_둘_다_반영된다() throws Exception {
        for (int round = 0; round < 5; round++) {
            long s = member(UserStatus.ACTIVE);
            writer.addDailyPayout(payout(s, 1_000L, DAY));
            for (int i = 0; i < 20; i++) {
                writer.save(draft(s, "n-" + i, 7L, "호스트", DAY.plusSeconds(10 + i)));
            }

            runTogether(List.of(() -> writer.addDailyPayout(payout(s, 2_000L, DAY.plusSeconds(3_600))),
                    () -> markAllRead(s)));

            assertThat(singleBundle(s).get("deleted_at")).isNull();
            assertThat(payloadNumber(s, "count")).isEqualTo(2L);
            assertThat(aliveOf(s)).isEqualTo(NotificationWriter.KEEP);
            assertThat(unreadOthersOf(s)).isZero();
        }
    }

    /**
     * 전체 읽음과 같은 잠금·갱신(NotificationQueryFacade.markAllRead와 같은 쿼리 순서). 퍼사드는 로그인 인가가 걸려 있어 저장소로 같은
     * 쿼리를 쓴다. {@code runTogether}에 넣으려고 결과 없이 null을 돌려준다
     */
    private NotificationWriteResult markAllRead(long receiver) {
        transaction.executeWithoutResult(status -> {
            List<Long> unread = repository.lockUnreadInIdOrder(receiver);
            if (!unread.isEmpty()) {
                repository.markReadAll(unread, Instant.now());
            }
        });
        return null;
    }

    /**
     * 묶음이 아닌 보이는 알림 중 안 읽은 수. 전체 읽음이 반영됐는지 본다(묶음은 전체 읽음 뒤에 갱신되면 다시 안 읽음이 될 수 있어 뺀다)
     */
    private int unreadOthersOf(long receiver) {
        return jdbc.queryForObject("select count(*) from notification.notification where receiver_user_id = ? "
                + "and kind <> ? and deleted_at is null and read_at is null", Integer.class, receiver, KIND);
    }

    private long member(UserStatus status) {
        long id = NEXT_ID.incrementAndGet();
        userSync.syncJoined(new UserDto(id, "회원" + id, Role.INFLUENCER, status, 0L));
        return id;
    }

    private static DailyPayout payout(long seller, long amount, Instant paidAt) {
        return new DailyPayout(NEXT_ID.incrementAndGet(), seller, amount, paidAt);
    }

    private List<Map<String, Object>> bundlesOf(long receiver) {
        return jdbc.queryForList("select * from notification.notification where receiver_user_id = ? and kind = ?",
                receiver, KIND);
    }

    private Map<String, Object> singleBundle(long receiver) {
        List<Map<String, Object>> bundles = bundlesOf(receiver);
        assertThat(bundles).hasSize(1);
        return bundles.getFirst();
    }

    private long payloadNumber(long receiver, String key) {
        return jdbc.queryForObject("select (payload ->> ?)::bigint from notification.notification "
                + "where receiver_user_id = ? and kind = ?", Long.class, key, receiver, KIND);
    }

    private Instant occurredAt(long receiver) {
        return jdbc.queryForObject("select occurred_at from notification.notification "
                + "where receiver_user_id = ? and kind = ?", Timestamp.class, receiver, KIND).toInstant();
    }

    private void markRead(long receiver) {
        jdbc.update("update notification.notification set read_at = now() where receiver_user_id = ? and kind = ?",
                receiver, KIND);
    }

    private int itemsOf(long receiver) {
        return jdbc.queryForObject("select count(*) from notification.notification_payout_item "
                + "where receiver_user_id = ?", Integer.class, receiver);
    }

    private int aliveOf(long receiver) {
        return jdbc.queryForObject("select count(*) from notification.notification "
                + "where receiver_user_id = ? and deleted_at is null", Integer.class, receiver);
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
