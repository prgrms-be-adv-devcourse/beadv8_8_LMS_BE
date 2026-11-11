package com.hapbang.coupon.domain;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.hapbang.coupon.domain.IssueReconciliationRule.DbResult;
import com.hapbang.coupon.domain.IssueReconciliationRule.Input;
import com.hapbang.coupon.domain.IssueReconciliationRule.Judgment;
import com.hapbang.coupon.domain.IssueReconciliationRule.Mismatch;
import com.hapbang.coupon.domain.IssueReconciliationRule.MissingUser;
import com.hapbang.coupon.domain.IssueReconciliationRule.SoldOutRepair;

// 수량 식 = DB 발급 완료 + 진행 중 + 거절 보상 대기 + Redis 재고. 같은 요청은 한 갈래로만 센다
class IssueReconciliationRuleTest {

    private static final Instant NOW = Instant.parse("2026-11-08T05:00:00Z");
    private static final Duration REPUBLISH_AFTER = Duration.ofSeconds(30);
    private static final long EPOCH = 1;
    private static final int QUANTITY = 10;

    private final Map<String, String> requests = new HashMap<>();
    private final Map<String, DbResult> db = new HashMap<>();
    private final Set<Long> users = new HashSet<>();
    private Long stock;
    private boolean soldOut;
    private Long epoch = EPOCH;
    private boolean stillAccepting = true;

    @Test
    void 확정_발급_진행_중_보상_대기와_재고의_합이_총수량이면_맞다() {
        issued("r1", 1);                 // 확정 발급(요청 기록 남음)
        db.put("r0", issuedRow(0));      // 확정 발급(요청 기록은 이미 정리)
        users.add(0L);
        accepted("r2", 2, NOW);          // 진행 중
        rejected("r3", 3, EPOCH, false); // 거절 보상 대기
        stock = 6L;

        Judgment judgment = judge();

        assertThat(judgment.matched()).isTrue();
        assertThat(judgment.dbIssued()).isEqualTo(2);
        assertThat(judgment.inProgress()).isEqualTo(1);
        assertThat(judgment.pendingCompensation()).isEqualTo(1);
    }

    @Test
    void 보상을_마친_거절은_어느_쪽에도_세지_않고_접수가_끝난_쿠폰에서만_정리_대상이다() {
        stillAccepting = false;
        rejected("r1", 1, EPOCH, true);
        stock = 10L;

        Judgment judgment = judge();

        assertThat(judgment.matched()).isTrue();
        assertThat(judgment.pendingCompensation()).isZero();
        assertThat(judgment.finishedRequestIds()).containsExactly("r1");
    }

    @Test
    void 접수가_열릴_수_있는_동안_보상을_마친_거절_기록은_남기고_식에_세지_않는다() {
        // 지우면 users에서도 빠진 회원이 같은 요청 ID로 다시 접수돼 재고가 한 번 더 차감된다
        rejected("r1", 1, EPOCH, true);
        stock = 10L;

        Judgment judgment = judge();

        assertThat(judgment.matched()).isTrue();
        assertThat(judgment.pendingCompensation()).isZero();
        assertThat(judgment.inProgress()).isZero();
        assertThat(judgment.finishedRequestIds()).isEmpty();
        assertThat(judgment.requestsLeft()).isEqualTo(1);
    }

    @Test
    void DB를_읽은_뒤_거절_확정과_보상이_끝난_요청은_진행_중으로_세지_않는다() {
        // DB에는 아직 없지만 Redis는 보상 표식과 돌려받은 재고를 함께 보여 준다
        requests.put("r1", record(1, NOW, false, true));
        stock = 10L;

        Judgment judgment = judge();

        assertThat(judgment.matched()).isTrue();
        assertThat(judgment.inProgress()).isZero();
        assertThat(judgment.finishedRequestIds()).isEmpty();
    }

    @Test
    void 이전_epoch의_보상_전_거절은_재구축한_재고에_없으므로_보상_대기로_세지_않는다() {
        rejected("r1", 1, EPOCH - 1, false);
        users.remove(1L);
        stock = 10L;

        Judgment judgment = judge();

        assertThat(judgment.matched()).isTrue();
        assertThat(judgment.pendingCompensation()).isZero();
        assertThat(judgment.finishedRequestIds()).isEmpty();
        assertThat(judgment.requestsLeft()).isEqualTo(1);
    }

    @Test
    void 접수가_끝난_쿠폰의_이전_epoch_기록은_DB_결과가_있으면_보상_표식과_무관하게_정리_대상이고_식에_세지_않는다() {
        stillAccepting = false;
        epoch = EPOCH + 1;
        requests.put("issued", epochRecord(1, EPOCH, false));
        db.put("issued", issuedRow(1));
        users.add(1L);
        requests.put("rejected", epochRecord(2, EPOCH, false));
        db.put("rejected", new DbResult(2, CouponIssueResult.REJECTED, EPOCH));
        requests.put("compensated", epochRecord(3, EPOCH, true));
        db.put("compensated", new DbResult(3, CouponIssueResult.REJECTED, EPOCH));
        stock = 9L;

        Judgment judgment = judge();

        assertThat(judgment.matched()).isTrue();
        assertThat(judgment.pendingCompensation()).isZero();
        assertThat(judgment.finishedRequestIds()).containsExactlyInAnyOrder("issued", "rejected", "compensated");
        assertThat(judgment.requestsLeft()).isZero();
        assertThat(judgment.staleUnresolved()).isZero();
    }

    @Test
    void 접수가_열릴_수_있는_동안_이전_epoch_기록은_ISSUED만_정리_대상이고_REJECTED는_남긴다() {
        epoch = EPOCH + 1;
        requests.put("issued", epochRecord(1, EPOCH, false));
        db.put("issued", issuedRow(1));
        users.add(1L);
        requests.put("rejected", epochRecord(2, EPOCH, false));
        db.put("rejected", new DbResult(2, CouponIssueResult.REJECTED, EPOCH));
        requests.put("compensated", epochRecord(3, EPOCH, true));
        db.put("compensated", new DbResult(3, CouponIssueResult.REJECTED, EPOCH));
        stock = 9L;

        Judgment judgment = judge();

        assertThat(judgment.matched()).isTrue();
        assertThat(judgment.pendingCompensation()).isZero();
        assertThat(judgment.finishedRequestIds()).containsExactly("issued");
        assertThat(judgment.requestsLeft()).isEqualTo(2);
        assertThat(judgment.staleUnresolved()).isZero();
    }

    @Test
    void DB_결과가_없는_이전_epoch_기록은_남기고_진행_중으로_세지_않으며_알릴_수를_센다() {
        epoch = EPOCH + 1;
        requests.put("lost", epochRecord(1, EPOCH, false));
        requests.put("lost-compensated", epochRecord(2, EPOCH, true));
        stock = 10L;

        Judgment judgment = judge();

        assertThat(judgment.matched()).isTrue();
        assertThat(judgment.inProgress()).isZero();
        assertThat(judgment.waitingRepublish()).isZero();
        assertThat(judgment.missingUsers()).isEmpty();
        assertThat(judgment.finishedRequestIds()).isEmpty();
        assertThat(judgment.requestsLeft()).isEqualTo(2);
        assertThat(judgment.staleUnresolved()).isEqualTo(1);
    }

    @Test
    void 지금_epoch_기록은_기존_규칙대로_보상_전_거절을_보상_대기로_센다() {
        requests.put("rejected", epochRecord(1, EPOCH, false));
        db.put("rejected", new DbResult(1, CouponIssueResult.REJECTED, EPOCH));
        users.add(1L);
        stock = 9L;

        Judgment judgment = judge();

        assertThat(judgment.matched()).isTrue();
        assertThat(judgment.pendingCompensation()).isEqualTo(1);
        assertThat(judgment.finishedRequestIds()).isEmpty();
    }

    @Test
    void 재고가_음수면_음수_재고_불일치다() {
        stock = -1L;

        assertThat(judge().mismatch()).isEqualTo(Mismatch.NEGATIVE_STOCK);
    }

    @Test
    void DB_발급_완료가_총수량을_넘으면_DB_총량_초과다() {
        for (int i = 0; i <= QUANTITY; i++) {
            issued("r" + i, i);
        }
        stock = 0L;

        assertThat(judge().mismatch()).isEqualTo(Mismatch.DB_OVER_QUANTITY);
    }

    @Test
    void 식이_맞지_않으면_원인_불명이다() {
        accepted("r1", 1, NOW);
        stock = 10L; // 접수했는데 재고가 줄지 않았다

        Judgment judgment = judge();

        assertThat(judgment.mismatch()).isEqualTo(Mismatch.UNEXPLAINED);
        assertThat(judgment.describe()).contains("inProgress=1", "stock=10");
    }

    @Test
    void 재고나_epoch가_없거나_읽을_수_없는_요청_기록이_있으면_원인_불명이다() {
        stock = null;
        assertThat(judge().mismatch()).isEqualTo(Mismatch.UNEXPLAINED);

        stock = 10L;
        epoch = null;
        assertThat(judge().mismatch()).isEqualTo(Mismatch.UNEXPLAINED);

        epoch = EPOCH;
        requests.put("bad", "not-a-record");
        Judgment judgment = judge();
        assertThat(judgment.mismatch()).isEqualTo(Mismatch.UNEXPLAINED);
        assertThat(judgment.malformed()).isEqualTo(1);
    }

    @Test
    void 재발행_대기는_대기_시간이_지난_진행_중만_세고_전송_표식과_무관하며_보상한_요청은_빼다() {
        accepted("old", 1, NOW.minus(REPUBLISH_AFTER));
        requests.put("published", record(2, NOW.minus(REPUBLISH_AFTER).minusSeconds(1), true, false));
        users.add(2L);
        accepted("fresh", 3, NOW.minus(REPUBLISH_AFTER).plusMillis(1));
        requests.put("compensated", record(4, NOW.minusSeconds(60), false, true));
        stock = 7L;

        Judgment judgment = judge();

        assertThat(judgment.matched()).isTrue();
        assertThat(judgment.inProgress()).isEqualTo(3);
        assertThat(judgment.waitingRepublish()).isEqualTo(2);
    }

    @Test
    void users에_없는_확정_발급_진행_중_회원은_다시_넣을_대상이고_어디에도_없는_회원은_세기만_한다() {
        db.put("r0", issuedRow(10));             // 확정 발급, users에 없음
        requests.put("r1", record(11, NOW, false, false)); // 진행 중, users에 없음
        users.add(99L);                          // 어디에도 없음
        stock = 8L;

        Judgment judgment = judge();

        assertThat(judgment.matched()).isTrue();
        assertThat(judgment.missingUsers()).containsExactlyInAnyOrder(new MissingUser(10, null),
                new MissingUser(11, "r1"));
        assertThat(judgment.extraUsers()).isEqualTo(1);
    }

    @Test
    void 매진_표시는_재고_0일_때만_있어야_한다() {
        stock = 0L;
        for (int i = 0; i < QUANTITY; i++) {
            issued("r" + i, i);
        }
        assertThat(judge().soldOutRepair()).isEqualTo(SoldOutRepair.SET);

        soldOut = true;
        assertThat(judge().soldOutRepair()).isEqualTo(SoldOutRepair.NONE);

        db.clear();
        requests.clear();
        users.clear();
        stock = 10L;
        assertThat(judge().soldOutRepair()).isEqualTo(SoldOutRepair.CLEAR);
    }

    @Test
    void DB_최종_결과가_확인된_요청만_정리_대상이고_남은_요청_수를_센다() {
        stillAccepting = false;
        issued("issued", 1);
        rejected("compensated", 2, EPOCH, true);
        rejected("pending", 3, EPOCH, false);
        accepted("progress", 4, NOW);
        stock = 7L;

        Judgment judgment = judge();

        assertThat(judgment.matched()).isTrue();
        assertThat(judgment.finishedRequestIds()).containsExactlyInAnyOrder("issued", "compensated");
        assertThat(judgment.requestsLeft()).isEqualTo(2);
    }

    @Test
    void DB_결과가_없는_진행_중이나_이전_epoch_기록이나_읽을_수_없는_기록이_있으면_key를_남긴다() {
        stillAccepting = false;
        accepted("progress", 1, NOW);
        stock = 9L;
        assertThat(judge().retainKeys()).isTrue();

        requests.clear();
        users.clear();
        epoch = EPOCH + 1;
        requests.put("lost", epochRecord(1, EPOCH, false));
        stock = 10L;
        assertThat(judge().retainKeys()).isTrue();

        requests.clear();
        requests.put("broken", "broken");
        assertThat(judge().retainKeys()).isTrue();
    }

    @Test
    void DB_결과가_모두_있거나_보상을_마친_기록만_남으면_key를_남기지_않는다() {
        stillAccepting = false;
        issued("issued", 1);
        rejected("pending", 2, EPOCH, false);       // 보상 대기여도 DB 결과는 있다
        requests.put("compensated-lost", record(3, NOW, true, true)); // DB를 읽은 뒤 거절 확정·보상
        stock = 8L;

        Judgment judgment = judge();

        assertThat(judgment.matched()).isTrue();
        assertThat(judgment.retainKeys()).isFalse();
    }

    @Test
    void 발급_중이거나_중단된_쿠폰은_발급_종료와_여유_전까지_접수가_열릴_수_있다() {
        Instant end = NOW.plusSeconds(10);
        Duration grace = Duration.ofSeconds(30);

        assertThat(IssueReconciliationRule.stillAccepting(CouponStatus.OPEN, "OPEN", end, NOW, grace)).isTrue();
        assertThat(IssueReconciliationRule.stillAccepting(CouponStatus.SUSPENDED, "SUSPENDED", end, NOW, grace))
                .isTrue();
        // 종료 직후라도 여유 안이면 아직 열린 것으로 본다(종료 직전에 시각을 읽은 접수가 늦게 닿을 수 있다)
        assertThat(IssueReconciliationRule.stillAccepting(CouponStatus.OPEN, "OPEN", end, end.plusSeconds(29), grace))
                .isTrue();
        assertThat(IssueReconciliationRule.stillAccepting(CouponStatus.OPEN, "OPEN", end, end.plus(grace), grace))
                .isFalse();
    }

    @Test
    void 다시_열_수_없는_쿠폰은_Redis가_OPEN이_아니면_접수가_끝났다() {
        Instant end = NOW.plusSeconds(10);
        Duration grace = Duration.ofSeconds(30);

        assertThat(IssueReconciliationRule.stillAccepting(CouponStatus.CLOSED, "CLOSED", end, NOW, grace)).isFalse();
        assertThat(IssueReconciliationRule.stillAccepting(CouponStatus.REJECTED, null, end, NOW, grace)).isFalse();
        // DB와 Redis가 어긋나 Redis가 아직 OPEN이면 보수적으로 남긴다
        assertThat(IssueReconciliationRule.stillAccepting(CouponStatus.CLOSED, "OPEN", end, NOW, grace)).isTrue();
    }

    private Judgment judge() {
        return IssueReconciliationRule.judge(new Input(QUANTITY, epoch, stock, soldOut, users, requests, db, NOW,
                REPUBLISH_AFTER, stillAccepting));
    }

    private void accepted(String requestId, long memberId, Instant acceptedAt) {
        requests.put(requestId, record(memberId, acceptedAt, false, false));
        users.add(memberId);
    }

    private void issued(String requestId, long memberId) {
        requests.put(requestId, record(memberId, NOW.minusSeconds(5), true, false));
        db.put(requestId, issuedRow(memberId));
        users.add(memberId);
    }

    private void rejected(String requestId, long memberId, long rowEpoch, boolean compensated) {
        requests.put(requestId, record(memberId, NOW.minusSeconds(5), true, compensated));
        db.put(requestId, new DbResult(memberId, CouponIssueResult.REJECTED, rowEpoch));
        if (!compensated) {
            users.add(memberId);
        }
    }

    private static DbResult issuedRow(long memberId) {
        return new DbResult(memberId, CouponIssueResult.ISSUED, EPOCH);
    }

    private static String epochRecord(long memberId, long recordEpoch, boolean compensated) {
        Instant acceptedAt = NOW.minusSeconds(60);
        return new IssueRequestRecord(memberId, acceptedAt, acceptedAt.plus(Duration.ofDays(30)), recordEpoch, true,
                compensated).format();
    }

    private static String record(long memberId, Instant acceptedAt, boolean published, boolean compensated) {
        return new IssueRequestRecord(memberId, acceptedAt, acceptedAt.plus(Duration.ofDays(30)), published,
                compensated).format();
    }
}
