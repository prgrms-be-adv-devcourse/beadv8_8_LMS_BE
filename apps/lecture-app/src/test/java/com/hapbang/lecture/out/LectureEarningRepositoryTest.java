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
import com.hapbang.lecture.domain.LectureHpTask;
import com.hapbang.lecture.domain.LectureHpTaskPurpose;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.fixture.LectureOrderFixture;
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
    private LectureHpTaskRepository lectureHpTaskRepository;

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
        LectureOrder failedOnce = payoutPending(DUE_PURCHASE, 1_000L);
        retrying(payoutTask(failedOnce), NOW_AT.minusDays(1));
        confirmed(NOT_DUE_PURCHASE, 1_000L);
        LectureOrder dead = payoutPending(DUE_PURCHASE, 1_000L);
        dead(payoutTask(dead), NOW_AT.minusDays(1));
        flushAndClear();

        // 처리 중은 지급 배치가 선점할 항목과 지급 처리 중 항목이다. 재시도 중인 작업도 처리 중이고, 작업이 DEAD면 실패다 (D70 (3))
        assertThat(lectureOrderRepository.findPayoutDueItemIds(NOW_AT))
                .containsExactlyInAnyOrder(due, sameDueLater, itemId(rejected));
        assertThat(itemIds(PROCESSING))
                .containsExactlyInAnyOrder(due, sameDueLater, itemId(rejected), itemId(failedOnce));
        assertThat(itemIds(FAILED)).containsExactly(itemId(dead));
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
    void 지급_작업이_DEAD인_항목만_실패이고_재시도_중이거나_관리자가_다시_시도하면_처리_중이다() {
        LectureOrder failedOnce = payoutPending(DUE_PURCHASE, 1_000L);
        retrying(payoutTask(failedOnce), NOW_AT.minusDays(2));
        LectureOrder dead = payoutPending(DUE_PURCHASE, 2_000L);
        dead(payoutTask(dead), NOW_AT.minusDays(2));
        // 관리자가 다시 시도하면 작업이 PENDING이 되어 다시 처리 중이다 (D57 (9))
        LectureOrder retried = payoutPending(DUE_PURCHASE, 4_000L);
        LectureHpTask retriedTask = dead(payoutTask(retried), NOW_AT.minusDays(2));
        retriedTask.retryManually(ADMIN_ID, NOW_AT.minusDays(1));
        lectureHpTaskRepository.save(retriedTask);
        flushAndClear();

        assertThat(summary()).containsOnly(entry(FAILED, total(1, 2_000L)), entry(PROCESSING, total(2, 5_000L)));
    }

    @Test
    void 지급_완료_구매_취소_반환은_요약에서_빠진다() {
        LectureOrder paidOut = confirmed(DUE_PURCHASE, 1_000L);
        LectureOrderFixture.payOut(paidOut, itemId(paidOut), NOW_AT.minusDays(1));
        LectureOrder canceled = purchased(RECENT_PURCHASE, 2_000L);
        LectureOrderFixture.cancel(canceled, itemId(canceled), RECENT_PURCHASE.plusHours(1));
        LectureOrder returned = confirmed(NOT_DUE_PURCHASE, 4_000L);
        fileObjection(returned, NOT_DUE_PURCHASE.plusDays(1));
        LectureOrderFixture.acceptAndReturn(returned, itemId(returned), INQUIRY_ID, ADMIN_ID,
                NOT_DUE_PURCHASE.plusDays(2));
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
        lectureOrderRepository.save(LectureOrderFixture.order(BUYER_ID, List.of(lecture(1_000L), lecture(2_000L),
                otherSellers), RECENT_PURCHASE));
        flushAndClear();

        assertThat(summary()).containsOnly(entry(CONFIRMATION_PENDING, total(2, 3_000L)));
        assertThat(lectureOrderRepository.findEarnings(SELLER_ID, UNPAID, NOW_AT, PageRequest.of(0, 10))
                .getTotalElements()).isEqualTo(2);
    }

    @Test
    void 결제가_끝나지_않은_항목은_요약과_목록에_없다() {
        lectureOrderRepository.save(LectureOrderFixture.pending(BUYER_ID, List.of(lecture(1_000L)), RECENT_PURCHASE));
        LectureOrder failed = lectureOrderRepository.save(
                LectureOrderFixture.pending(BUYER_ID, List.of(lecture(2_000L)), RECENT_PURCHASE));
        failed.markHoldFailed();
        flushAndClear();

        assertThat(summary()).isEmpty();
        assertThat(lectureOrderRepository.findEarnings(SELLER_ID, ALL, NOW_AT, PageRequest.of(0, 10))).isEmpty();
    }

    @Test
    void 다른_판매자의_항목은_요약과_목록에_없다() {
        Lecture otherSellers = lecture(3_000L);
        ReflectionTestUtils.setField(otherSellers, "sellerUserId", OTHER_SELLER_ID);
        lectureOrderRepository.save(LectureOrderFixture.order(BUYER_ID, List.of(otherSellers), RECENT_PURCHASE));
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
        LectureOrder failed = payoutPending(DUE_PURCHASE, 1_000L);
        dead(payoutTask(failed), NOW_AT.minusDays(1));
        purchased(RECENT_PURCHASE, 1_000L);
        LectureOrder paidOut = confirmed(DUE_PURCHASE, 1_000L);
        LectureOrderFixture.payOut(paidOut, itemId(paidOut), NOW_AT.minusDays(1));
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
        LectureOrder failed = payoutPending(DUE_PURCHASE, 1_000L);
        dead(payoutTask(failed), failedAt);
        LectureOrder payingOut = payoutPending(DUE_PURCHASE, 1_000L);
        payoutTask(payingOut);
        LocalDateTime cancelRequestedAt = RECENT_PURCHASE.plusHours(2);
        LectureOrder cancelPending = purchased(RECENT_PURCHASE, 1_000L);
        cancelPending.requestCancelItem(itemId(cancelPending), cancelRequestedAt);
        releaseTask(cancelPending, LectureHpTaskPurpose.CANCEL, cancelRequestedAt);
        LocalDateTime acceptedAt = NOT_DUE_PURCHASE.plusDays(3);
        LectureOrder returnPending = confirmed(NOT_DUE_PURCHASE, 1_000L);
        fileObjection(returnPending, NOT_DUE_PURCHASE.plusDays(1));
        returnPending.resolveObjection(itemId(returnPending), INQUIRY_ID, true, ADMIN_ID, acceptedAt);
        releaseTask(returnPending, LectureHpTaskPurpose.RETURN, acceptedAt);
        LocalDateTime paidOutAt = NOW_AT.minusDays(2);
        LectureOrder completed = confirmed(DUE_PURCHASE, 1_000L);
        LectureOrderFixture.payOut(completed, itemId(completed), paidOutAt);
        LocalDateTime canceledAt = RECENT_PURCHASE.plusHours(1);
        LectureOrder canceled = purchased(RECENT_PURCHASE, 1_000L);
        LectureOrderFixture.cancel(canceled, itemId(canceled), canceledAt);
        LocalDateTime returnedAt = NOT_DUE_PURCHASE.plusDays(2);
        LectureOrder returned = confirmed(NOT_DUE_PURCHASE, 1_000L);
        fileObjection(returned, NOT_DUE_PURCHASE.plusDays(1));
        LectureOrderFixture.acceptAndReturn(returned, itemId(returned), INQUIRY_ID, ADMIN_ID, returnedAt);
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
        assertRow(rows, payingOut, PROCESSING, LocalDateTime.of(2026, 11, 1, 0, 0));
        // 반환 처리 중은 지급하지 않을 것이 확실해 취소이고, 상태 변경 시각은 반환 작업의 등록 시각이다 (D70 (3))
        assertRow(rows, cancelPending, CANCELED, cancelRequestedAt);
        assertRow(rows, returnPending, CANCELED, acceptedAt);
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
        return lectureOrderRepository.save(LectureOrderFixture.order(BUYER_ID, List.of(lecture(priceHp)), purchasedAt));
    }

    /** 구매 확정하고 지급 예정 시각에 지급을 시작한 주문 */
    private LectureOrder payoutPending(LocalDateTime purchasedAt, long priceHp) {
        LectureOrder order = confirmed(purchasedAt, priceHp);
        order.startPayoutItem(itemId(order), itemOf(order).getPayoutScheduledAt());
        return order;
    }

    private LectureHpTask payoutTask(LectureOrder order) {
        OrderItem item = itemOf(order);
        return lectureHpTaskRepository.save(LectureHpTask.create(LectureHpTaskPurpose.PAYOUT,
                "LECTURE-PAYOUT-" + item.getId(), order.getId(), item.getId(), item.getBuyerUserId(),
                item.getSellerUserId(), item.getPriceHp(), item.getPayoutScheduledAt()));
    }

    private void releaseTask(LectureOrder order, LectureHpTaskPurpose purpose, LocalDateTime createdAt) {
        OrderItem item = itemOf(order);
        lectureHpTaskRepository.save(LectureHpTask.create(purpose, "LECTURE-" + purpose + "-" + item.getId(),
                order.getId(), item.getId(), item.getBuyerUserId(), null, item.getPriceHp(), createdAt));
    }

    private LectureHpTask retrying(LectureHpTask task, LocalDateTime failedAt) {
        task.scheduleRetry("WALLET_NOT_FOUND", failedAt.plusMinutes(1), failedAt);
        return lectureHpTaskRepository.save(task);
    }

    private LectureHpTask dead(LectureHpTask task, LocalDateTime failedAt) {
        task.markDead("PAYOUT_ALREADY_SETTLED", failedAt);
        return lectureHpTaskRepository.save(task);
    }

    private static OrderItem itemOf(LectureOrder order) {
        return order.getItems().getFirst();
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
