package com.hapbang.lecture.out;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static com.hapbang.shared.lecture.domain.LectureEarningStatus.*;
import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.shared.lecture.domain.LectureEarningStatus;
import com.hapbang.testsupport.RepositoryTest;

import jakarta.persistence.EntityManager;

/** 판매 수익 분류·집계·목록 쿼리 (#300). 분류 기준은 {@link LectureOrderRepository#EARNING_STATUS} 하나다 */
@RepositoryTest
class LectureEarningRepositoryTest {

    private static final Long BUYER_ID = OTHER_USER_ID;
    private static final Long OTHER_SELLER_ID = 4L;
    private static final Long INQUIRY_ID = 500L;
    private static final Set<LectureEarningStatus> UNPAID = EnumSet.of(CONFIRMATION_PENDING, SCHEDULED, ON_HOLD,
            PROCESSING, FAILED);
    private static final Set<LectureEarningStatus> ALL = EnumSet.allOf(LectureEarningStatus.class);

    /** 조회 시각. 지급 예정 시각(구매일 + 31일 00:00)이 이 시각 이전이면 지급 대상이다 */
    private static final LocalDateTime NOW_AT = LocalDateTime.of(2026, 11, 5, 12, 0);
    /** 지급 예정 시각 11-01 00:00 (지났음), 자동 구매 확정 시각 10-09 00:00 */
    private static final LocalDateTime DUE_PURCHASE = LocalDateTime.of(2026, 10, 1, 10, 0);
    /** 지급 예정 시각 11-21 00:00 (전), 자동 구매 확정 시각 10-28 00:00 */
    private static final LocalDateTime NOT_DUE_PURCHASE = LocalDateTime.of(2026, 10, 20, 10, 0);
    /** 자동 구매 확정 시각 11-09 00:00 (전) */
    private static final LocalDateTime RECENT_PURCHASE = LocalDateTime.of(2026, 11, 1, 10, 0);

    @Autowired
    private LectureOrderRepository lectureOrderRepository;

    @Autowired
    private EntityManager entityManager;

    private long nextLectureId = 100L;

    @Test
    void 구매_확정_전_항목은_확정_대기로_요약된다() {
        purchased(RECENT_PURCHASE, 1_000L);
        purchased(RECENT_PURCHASE.plusHours(1), 2_000L);
        flushAndClear();

        assertThat(summary()).containsOnly(entry(CONFIRMATION_PENDING, total(2, 3_000L)));
    }

    @Test
    void 구매_확정_뒤_지급_예정_시각_전이면_지급_예정이다() {
        confirmed(NOT_DUE_PURCHASE, 5_000L);
        flushAndClear();

        assertThat(summary()).containsOnly(entry(SCHEDULED, total(1, 5_000L)));
    }

    @Test
    void 지급_예정_시각이_지난_지급_대상은_처리_중이고_지급_배치_대상과_같다() {
        Long due = itemId(confirmed(DUE_PURCHASE, 1_000L));
        Long sameDueLater = itemId(confirmed(DUE_PURCHASE.plusHours(1), 1_000L));
        LectureOrder rejected = confirmed(DUE_PURCHASE, 1_000L);
        fileObjection(rejected, DUE_PURCHASE.plusDays(1));
        rejected.resolveObjection(itemId(rejected), INQUIRY_ID, false, ADMIN_ID, NOW_AT.minusDays(1));
        LectureOrder failedOnce = confirmed(DUE_PURCHASE, 1_000L);
        failedOnce.recordPayoutFailure(itemId(failedOnce), "WALLET_NOT_FOUND", false, NOW_AT.minusDays(1));
        confirmed(NOT_DUE_PURCHASE, 1_000L);
        LectureOrder quarantined = confirmed(DUE_PURCHASE, 1_000L);
        quarantined.recordPayoutFailure(itemId(quarantined), "PAYOUT_ALREADY_SETTLED", true, NOW_AT.minusDays(1));
        flushAndClear();

        // 처리 중은 지급 배치 대상과 같다. 일시 실패한 항목도 다음 배치가 다시 시도하므로 처리 중이고, 격리된 항목만 빠진다
        assertThat(itemIds(PROCESSING))
                .containsExactlyInAnyOrder(due, sameDueLater, itemId(rejected), itemId(failedOnce))
                .containsExactlyInAnyOrderElementsOf(lectureOrderRepository.findPayoutDueItemIds(NOW_AT));
        assertThat(itemIds(FAILED)).containsExactly(itemId(quarantined));
    }

    @Test
    void 이의_제기_접수_중이면_구매_확정_전후_모두_지급_중단이다() {
        fileObjection(purchased(RECENT_PURCHASE, 1_000L), RECENT_PURCHASE.plusHours(1));
        fileObjection(confirmed(NOT_DUE_PURCHASE, 2_000L), NOT_DUE_PURCHASE.plusDays(1));
        LectureOrder dueAndFiled = confirmed(DUE_PURCHASE, 4_000L);
        fileObjection(dueAndFiled, DUE_PURCHASE.plusDays(1));
        flushAndClear();

        assertThat(summary()).containsOnly(entry(ON_HOLD, total(3, 7_000L)));
    }

    @Test
    void 이의_제기가_기각되면_원래_상태로_돌아간다() {
        LectureOrder purchased = purchased(RECENT_PURCHASE, 1_000L);
        fileObjection(purchased, RECENT_PURCHASE.plusHours(1));
        purchased.resolveObjection(itemId(purchased), INQUIRY_ID, false, ADMIN_ID, RECENT_PURCHASE.plusDays(1));
        LectureOrder confirmed = confirmed(NOT_DUE_PURCHASE, 2_000L);
        fileObjection(confirmed, NOT_DUE_PURCHASE.plusDays(1));
        confirmed.resolveObjection(itemId(confirmed), INQUIRY_ID, false, ADMIN_ID, NOT_DUE_PURCHASE.plusDays(2));
        flushAndClear();

        assertThat(summary()).containsOnly(entry(CONFIRMATION_PENDING, total(1, 1_000L)),
                entry(SCHEDULED, total(1, 2_000L)));
    }

    @Test
    void 격리된_항목만_실패이고_일시_실패와_격리_해제는_처리_중이다() {
        LectureOrder failedOnce = confirmed(DUE_PURCHASE, 1_000L);
        failedOnce.recordPayoutFailure(itemId(failedOnce), "WALLET_NOT_FOUND", false, NOW_AT.minusDays(2));
        LectureOrder quarantined = confirmed(DUE_PURCHASE, 2_000L);
        quarantined.recordPayoutFailure(itemId(quarantined), "PAYOUT_ALREADY_SETTLED", true, NOW_AT.minusDays(2));
        // 격리를 풀면 다음 지급 배치가 다시 시도한다 (D54)
        LectureOrder released = confirmed(DUE_PURCHASE, 4_000L);
        released.recordPayoutFailure(itemId(released), "PAYOUT_ALREADY_SETTLED", true, NOW_AT.minusDays(2));
        released.releasePayoutQuarantine(itemId(released));
        flushAndClear();

        assertThat(summary()).containsOnly(entry(FAILED, total(1, 2_000L)), entry(PROCESSING, total(2, 5_000L)));
    }

    @Test
    void 지급_완료_구매_취소_반환은_요약에서_빠진다() {
        LectureOrder paidOut = confirmed(DUE_PURCHASE, 1_000L);
        paidOut.payOutItem(itemId(paidOut), NOW_AT.minusDays(1));
        LectureOrder canceled = purchased(RECENT_PURCHASE, 2_000L);
        canceled.cancelItem(itemId(canceled), RECENT_PURCHASE.plusHours(1));
        LectureOrder returned = confirmed(NOT_DUE_PURCHASE, 4_000L);
        fileObjection(returned, NOT_DUE_PURCHASE.plusDays(1));
        returned.resolveObjection(itemId(returned), INQUIRY_ID, true, ADMIN_ID, NOT_DUE_PURCHASE.plusDays(2));
        purchased(RECENT_PURCHASE, 8_000L);
        flushAndClear();

        assertThat(summary()).containsOnly(entry(CONFIRMATION_PENDING, total(1, 8_000L)));
        assertThat(itemIds(Set.of(COMPLETED))).containsExactly(itemId(paidOut));
        assertThat(itemIds(Set.of(CANCELED))).containsExactlyInAnyOrder(itemId(canceled), itemId(returned));
    }

    @Test
    void 한_주문의_여러_항목은_항목마다_한_번씩_집계된다() {
        Lecture otherSellers = lecture(3_000L);
        ReflectionTestUtils.setField(otherSellers, "sellerUserId", OTHER_SELLER_ID);
        lectureOrderRepository.save(LectureOrder.place(BUYER_ID, List.of(lecture(1_000L), lecture(2_000L),
                otherSellers), Set.of(), RECENT_PURCHASE));
        flushAndClear();

        assertThat(summary()).containsOnly(entry(CONFIRMATION_PENDING, total(2, 3_000L)));
        assertThat(lectureOrderRepository.findEarnings(SELLER_ID, UNPAID, NOW_AT, PageRequest.of(0, 10))
                .getTotalElements()).isEqualTo(2);
    }

    @Test
    void 다른_판매자의_항목은_요약과_목록에_없다() {
        Lecture otherSellers = lecture(3_000L);
        ReflectionTestUtils.setField(otherSellers, "sellerUserId", OTHER_SELLER_ID);
        lectureOrderRepository.save(LectureOrder.place(BUYER_ID, List.of(otherSellers), Set.of(), RECENT_PURCHASE));
        flushAndClear();

        assertThat(summary()).isEmpty();
        assertThat(lectureOrderRepository.findEarnings(SELLER_ID, ALL, NOW_AT, PageRequest.of(0, 10))).isEmpty();
    }

    @Test
    void 목록은_상태_변경_최신순이고_같은_시각이면_항목_ID_역순이다() {
        Long oldest = itemId(purchased(RECENT_PURCHASE, 1_000L));
        Long sameTimeFirst = itemId(purchased(RECENT_PURCHASE.plusHours(1), 1_000L));
        Long sameTimeSecond = itemId(purchased(RECENT_PURCHASE.plusHours(1), 1_000L));
        // 구매는 가장 이르지만 이의 제기 접수가 가장 늦다
        LectureOrder filedLatest = confirmed(NOT_DUE_PURCHASE, 1_000L);
        fileObjection(filedLatest, RECENT_PURCHASE.plusHours(2));
        flushAndClear();

        Page<EarningRow> first = lectureOrderRepository.findEarnings(SELLER_ID, UNPAID, NOW_AT, PageRequest.of(0, 3));
        Page<EarningRow> second = lectureOrderRepository.findEarnings(SELLER_ID, UNPAID, NOW_AT,
                PageRequest.of(1, 3));

        assertThat(first.getTotalElements()).isEqualTo(4);
        assertThat(first.getContent()).extracting(row -> row.item().getId())
                .containsExactly(itemId(filedLatest), sameTimeSecond, sameTimeFirst);
        assertThat(second.getContent()).extracting(row -> row.item().getId()).containsExactly(oldest);
    }

    @Test
    void 지정한_상태의_항목만_나온다() {
        Long scheduled = itemId(confirmed(NOT_DUE_PURCHASE, 1_000L));
        LectureOrder failed = confirmed(DUE_PURCHASE, 1_000L);
        failed.recordPayoutFailure(itemId(failed), "PAYOUT_ALREADY_SETTLED", true, NOW_AT.minusDays(1));
        purchased(RECENT_PURCHASE, 1_000L);
        LectureOrder paidOut = confirmed(DUE_PURCHASE, 1_000L);
        paidOut.payOutItem(itemId(paidOut), NOW_AT.minusDays(1));
        flushAndClear();

        assertThat(itemIds(Set.of(SCHEDULED, FAILED))).containsExactly(itemId(failed), scheduled);
        assertThat(lectureOrderRepository.findEarnings(SELLER_ID, Set.of(SCHEDULED, FAILED), NOW_AT,
                PageRequest.of(0, 10)).getTotalElements()).isEqualTo(2);
    }

    @Test
    void 상태별_상태_변경_시각은_정한_컬럼에서_온다() {
        LectureOrder pending = purchased(RECENT_PURCHASE, 1_000L);
        LocalDateTime pendingRejectedAt = RECENT_PURCHASE.plusHours(3);
        LectureOrder pendingAfterRejection = purchased(RECENT_PURCHASE, 1_000L);
        fileObjection(pendingAfterRejection, RECENT_PURCHASE.plusHours(1));
        pendingAfterRejection.resolveObjection(itemId(pendingAfterRejection), INQUIRY_ID, false, ADMIN_ID,
                pendingRejectedAt);
        LectureOrder scheduled = confirmed(NOT_DUE_PURCHASE, 1_000L);
        LocalDateTime scheduledRejectedAt = NOT_DUE_PURCHASE.plusDays(3);
        LectureOrder scheduledAfterRejection = confirmed(NOT_DUE_PURCHASE, 1_000L);
        fileObjection(scheduledAfterRejection, NOT_DUE_PURCHASE.plusDays(1));
        scheduledAfterRejection.resolveObjection(itemId(scheduledAfterRejection), INQUIRY_ID, false, ADMIN_ID,
                scheduledRejectedAt);
        LectureOrder processing = confirmed(DUE_PURCHASE, 1_000L);
        // 지급 예정 시각(11-01 00:00) 뒤에 기각되면 그때부터 처리 중이다
        LocalDateTime processingRejectedAt = LocalDateTime.of(2026, 11, 3, 9, 0);
        LectureOrder processingAfterRejection = confirmed(DUE_PURCHASE, 1_000L);
        fileObjection(processingAfterRejection, DUE_PURCHASE.plusDays(1));
        processingAfterRejection.resolveObjection(itemId(processingAfterRejection), INQUIRY_ID, false, ADMIN_ID,
                processingRejectedAt);
        LocalDateTime filedAt = NOT_DUE_PURCHASE.plusDays(1);
        LectureOrder onHold = confirmed(NOT_DUE_PURCHASE, 1_000L);
        fileObjection(onHold, filedAt);
        LocalDateTime failedAt = NOW_AT.minusDays(1);
        LectureOrder failed = confirmed(DUE_PURCHASE, 1_000L);
        failed.recordPayoutFailure(itemId(failed), "PAYOUT_ALREADY_SETTLED", true, failedAt);
        LocalDateTime paidOutAt = NOW_AT.minusDays(2);
        LectureOrder completed = confirmed(DUE_PURCHASE, 1_000L);
        completed.payOutItem(itemId(completed), paidOutAt);
        LocalDateTime canceledAt = RECENT_PURCHASE.plusHours(1);
        LectureOrder canceled = purchased(RECENT_PURCHASE, 1_000L);
        canceled.cancelItem(itemId(canceled), canceledAt);
        LocalDateTime returnedAt = NOT_DUE_PURCHASE.plusDays(2);
        LectureOrder returned = confirmed(NOT_DUE_PURCHASE, 1_000L);
        fileObjection(returned, NOT_DUE_PURCHASE.plusDays(1));
        returned.resolveObjection(itemId(returned), INQUIRY_ID, true, ADMIN_ID, returnedAt);
        flushAndClear();

        Map<Long, EarningRow> rows = lectureOrderRepository.findEarnings(SELLER_ID, ALL, NOW_AT,
                        PageRequest.of(0, 20)).stream()
                .collect(Collectors.toMap(row -> row.item().getId(), row -> row));

        assertRow(rows, pending, CONFIRMATION_PENDING, RECENT_PURCHASE);
        assertRow(rows, pendingAfterRejection, CONFIRMATION_PENDING, pendingRejectedAt);
        assertRow(rows, scheduled, SCHEDULED, NOT_DUE_PURCHASE);
        assertRow(rows, scheduledAfterRejection, SCHEDULED, scheduledRejectedAt);
        assertRow(rows, processing, PROCESSING, LocalDateTime.of(2026, 11, 1, 0, 0));
        assertRow(rows, processingAfterRejection, PROCESSING, processingRejectedAt);
        assertRow(rows, onHold, ON_HOLD, filedAt);
        assertRow(rows, failed, FAILED, failedAt);
        assertRow(rows, completed, COMPLETED, paidOutAt);
        assertRow(rows, canceled, CANCELED, canceledAt);
        assertRow(rows, returned, CANCELED, returnedAt);
    }

    private static void assertRow(Map<Long, EarningRow> rows, LectureOrder order, LectureEarningStatus status,
                                  LocalDateTime statusUpdatedAt) {
        assertThat(rows.get(itemId(order)))
                .extracting(EarningRow::status, EarningRow::statusUpdatedAt)
                .containsExactly(status, statusUpdatedAt);
    }

    private Map<LectureEarningStatus, Totals> summary() {
        return lectureOrderRepository.summarizeEarnings(SELLER_ID, NOW_AT).stream()
                .collect(Collectors.toMap(EarningStatusTotal::status,
                        total -> new Totals(total.count(), total.amountHp())));
    }

    private static Totals total(long count, long amountHp) {
        return new Totals(count, amountHp);
    }

    private record Totals(long count, long amountHp) {
    }

    private List<Long> itemIds(LectureEarningStatus... statuses) {
        return itemIds(EnumSet.copyOf(Arrays.asList(statuses)));
    }

    private List<Long> itemIds(Set<LectureEarningStatus> statuses) {
        return lectureOrderRepository.findEarnings(SELLER_ID, statuses, NOW_AT, PageRequest.of(0, 20)).stream()
                .map(row -> row.item().getId())
                .toList();
    }

    private LectureOrder purchased(LocalDateTime purchasedAt, long priceHp) {
        return lectureOrderRepository.save(LectureOrder.place(BUYER_ID, List.of(lecture(priceHp)), Set.of(),
                purchasedAt));
    }

    /** 구매 시각에 바로 구매 확정한 주문 */
    private LectureOrder confirmed(LocalDateTime purchasedAt, long priceHp) {
        LectureOrder order = purchased(purchasedAt, priceHp);
        order.confirmItem(itemId(order), purchasedAt);
        return order;
    }

    private static void fileObjection(LectureOrder order, LocalDateTime filedAt) {
        order.fileObjection(itemId(order), BUYER_ID, INQUIRY_ID, "강의 내용이 소개와 다릅니다.", filedAt);
    }

    private Lecture lecture(long priceHp) {
        Lecture lecture = withId(onSaleLecture(), nextLectureId++);
        ReflectionTestUtils.setField(lecture, "priceHp", priceHp);
        return lecture;
    }

    private static Long itemId(LectureOrder order) {
        return order.getItems().getFirst().getId();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
