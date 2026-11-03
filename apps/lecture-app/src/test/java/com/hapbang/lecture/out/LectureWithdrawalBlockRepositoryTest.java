package com.hapbang.lecture.out;

import static com.hapbang.lecture.domain.LectureWithdrawalBlockCode.*;
import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureHpTask;
import com.hapbang.lecture.domain.LectureHpTaskPurpose;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.LectureWithdrawalBlockCode;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.fixture.LectureOrderFixture;
import com.hapbang.shared.lecture.domain.LectureEarningStatus;
import com.hapbang.testsupport.RepositoryTest;

import jakarta.persistence.EntityManager;

/** 탈퇴 막힌 이유 집계 쿼리 (#625, D75). 판매자 쪽은 판매 수익 분류({@link LectureOrderRepository#EARNING_STATUS})를 그대로 쓴다 */
@RepositoryTest
class LectureWithdrawalBlockRepositoryTest {

    private static final Long BUYER_ID = OTHER_USER_ID;
    private static final Long OTHER_SELLER_ID = 4L;
    private static final Long INQUIRY_ID = 500L;

    private static final LocalDateTime NOW_AT = LocalDateTime.of(2026, 11, 5, 12, 0);
    /** 지급 예정 시각 11-01 00:00 (지났음) */
    private static final LocalDateTime DUE_PURCHASE = LocalDateTime.of(2026, 10, 1, 10, 0);
    /** 지급 예정 시각 11-21 00:00 (전) */
    private static final LocalDateTime NOT_DUE_PURCHASE = LocalDateTime.of(2026, 10, 20, 10, 0);
    /** 지급 예정 시각 12-02 00:00 (전) */
    private static final LocalDateTime RECENT_PURCHASE = LocalDateTime.of(2026, 11, 1, 10, 0);

    @Autowired
    private LectureOrderRepository lectureOrderRepository;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private LectureHpTaskRepository lectureHpTaskRepository;

    @Autowired
    private EntityManager entityManager;

    private long nextLectureId = 100L;

    @Test
    void 구매자의_결제_처리_중과_반환_처리_중_항목을_처리_중으로_센다() {
        lectureOrderRepository.save(LectureOrderFixture.pending(BUYER_ID, List.of(lecture()), RECENT_PURCHASE));
        LectureOrder compensating = lectureOrderRepository.save(
                LectureOrderFixture.pending(BUYER_ID, List.of(lecture()), RECENT_PURCHASE));
        compensating.startHoldCompensation();
        LectureOrder cancelPending = purchased(RECENT_PURCHASE);
        cancelPending.requestCancelItem(itemId(cancelPending), RECENT_PURCHASE.plusHours(1));
        LectureOrder returnPending = confirmed(NOT_DUE_PURCHASE);
        fileObjection(returnPending, NOT_DUE_PURCHASE.plusDays(1));
        returnPending.resolveObjection(itemId(returnPending), INQUIRY_ID, true, ADMIN_ID, NOT_DUE_PURCHASE.plusDays(2));
        flushAndClear();

        assertThat(buyer()).containsOnlyKeys(LECTURE_ORDER_PROCESSING);
        assertThat(buyer().get(LECTURE_ORDER_PROCESSING).count()).isEqualTo(4);
    }

    @Test
    void 구매자의_보류_중_항목을_세고_가장_늦은_지급_예정_시각을_준다() {
        purchased(RECENT_PURCHASE);
        confirmed(NOT_DUE_PURCHASE);
        payoutPending(DUE_PURCHASE);
        flushAndClear();

        assertThat(buyer()).containsOnlyKeys(LECTURE_ORDER_HOLDING);
        assertThat(buyer().get(LECTURE_ORDER_HOLDING))
                .isEqualTo(new WithdrawalBlockTotal(LECTURE_ORDER_HOLDING, 3, LocalDateTime.of(2026, 12, 2, 0, 0), 0));
    }

    @Test
    void 이의_제기_접수_중_항목은_이의_제기로만_센다() {
        fileObjection(purchased(RECENT_PURCHASE), RECENT_PURCHASE.plusHours(1));
        fileObjection(confirmed(NOT_DUE_PURCHASE), NOT_DUE_PURCHASE.plusDays(1));
        LocalDateTime notFiledPayoutAt = LocalDateTime.of(2026, 11, 1, 0, 0);
        confirmed(DUE_PURCHASE);
        // 기각되면 다시 보류 중이다
        LectureOrder rejected = confirmed(DUE_PURCHASE);
        fileObjection(rejected, DUE_PURCHASE.plusDays(1));
        rejected.resolveObjection(itemId(rejected), INQUIRY_ID, false, ADMIN_ID, DUE_PURCHASE.plusDays(2));
        flushAndClear();

        assertThat(buyer().get(LECTURE_OBJECTION_FILED).count()).isEqualTo(2);
        // 이의 제기 항목의 지급 예정 시각(12-02, 11-21)이 보류 중의 가장 늦은 시각에 섞이지 않는다
        assertThat(buyer().get(LECTURE_ORDER_HOLDING))
                .isEqualTo(new WithdrawalBlockTotal(LECTURE_ORDER_HOLDING, 2, notFiledPayoutAt, 0));
    }

    @Test
    void 지급_작업이_DEAD면_날짜를_정할_수_없는_항목으로_센다() {
        LectureOrder dead = payoutPending(DUE_PURCHASE);
        LectureHpTask task = payoutTask(dead);
        task.markDead("PAYOUT_ALREADY_SETTLED", NOW_AT.minusDays(1));
        lectureHpTaskRepository.save(task);
        LectureOrder retrying = payoutPending(DUE_PURCHASE);
        LectureHpTask retryingTask = payoutTask(retrying);
        retryingTask.scheduleRetry("WALLET_NOT_FOUND", NOW_AT, NOW_AT.minusDays(1));
        lectureHpTaskRepository.save(retryingTask);
        flushAndClear();

        assertThat(buyer().get(LECTURE_ORDER_HOLDING))
                .extracting(WithdrawalBlockTotal::count, WithdrawalBlockTotal::undatableCount)
                .containsExactly(2L, 1L);
        assertThat(seller(SELLER_ID))
                .extracting(WithdrawalBlockTotal::count, WithdrawalBlockTotal::undatableCount)
                .containsExactly(2L, 1L);
    }

    @Test
    void 판매자의_지급_전_판매_건은_수익_미지급_다섯_상태와_같다() {
        purchased(RECENT_PURCHASE);
        confirmed(NOT_DUE_PURCHASE);
        confirmed(DUE_PURCHASE);
        payoutPending(DUE_PURCHASE);
        fileObjection(confirmed(NOT_DUE_PURCHASE), NOT_DUE_PURCHASE.plusDays(1));
        LectureOrder dead = payoutPending(DUE_PURCHASE);
        LectureHpTask task = payoutTask(dead);
        task.markDead("PAYOUT_ALREADY_SETTLED", NOW_AT.minusDays(1));
        lectureHpTaskRepository.save(task);
        finishedOrders();
        flushAndClear();

        Map<LectureEarningStatus, EarningStatusTotal> earnings = lectureOrderRepository
                .summarizeEarnings(SELLER_ID, NOW_AT).stream()
                .collect(Collectors.toMap(EarningStatusTotal::status, total -> total));
        long unpaid = earnings.values().stream().mapToLong(EarningStatusTotal::count).sum();
        long undatable = earnings.entrySet().stream()
                .filter(entry -> !entry.getKey().hasPayoutSchedule())
                .mapToLong(entry -> entry.getValue().count())
                .sum();

        assertThat(unpaid).isEqualTo(6);
        assertThat(undatable).isEqualTo(2);
        assertThat(seller(SELLER_ID)).isEqualTo(new WithdrawalBlockTotal(LECTURE_SALE_PAYOUT_PENDING, unpaid,
                LocalDateTime.of(2026, 12, 2, 0, 0), undatable));
    }

    @Test
    void 결제_실패와_끝난_주문은_세지_않는다() {
        LectureOrder failed = lectureOrderRepository.save(
                LectureOrderFixture.pending(BUYER_ID, List.of(lecture()), RECENT_PURCHASE));
        failed.markHoldFailed();
        finishedOrders();
        flushAndClear();

        assertThat(buyer()).isEmpty();
        assertThat(seller(SELLER_ID)).isEqualTo(new WithdrawalBlockTotal(LECTURE_SALE_PAYOUT_PENDING, 0, null, 0));
    }

    @Test
    void 다른_회원의_주문은_세지_않는다() {
        Lecture otherSellers = lecture();
        ReflectionTestUtils.setField(otherSellers, "sellerUserId", OTHER_SELLER_ID);
        lectureOrderRepository.save(LectureOrderFixture.order(BUYER_ID, List.of(otherSellers), RECENT_PURCHASE));
        flushAndClear();

        // 구매자와 판매자는 서로의 집계에 없다
        assertThat(lectureOrderRepository.summarizeBuyerWithdrawalBlocks(SELLER_ID, NOW_AT)).isEmpty();
        assertThat(lectureOrderRepository.summarizeBuyerWithdrawalBlocks(OTHER_SELLER_ID, NOW_AT)).isEmpty();
        assertThat(seller(SELLER_ID).count()).isZero();
        assertThat(seller(BUYER_ID).count()).isZero();
        assertThat(seller(OTHER_SELLER_ID).count()).isEqualTo(1);
    }

    @Test
    void 판매_중이거나_검수_대기인_삭제되지_않은_강의만_센다() {
        lectureRepository.save(onSaleLecture());
        lectureRepository.save(pendingLecture());
        lectureRepository.save(rejectedLecture());
        Lecture deleted = onSaleLecture();
        deleted.delete(SELLER_ID, NOW);
        lectureRepository.save(deleted);
        Lecture otherSellers = onSaleLecture();
        ReflectionTestUtils.setField(otherSellers, "sellerUserId", OTHER_SELLER_ID);
        lectureRepository.save(otherSellers);
        flushAndClear();

        assertThat(lectureRepository.countWithdrawalBlockingLectures(SELLER_ID)).isEqualTo(2);
        assertThat(lectureRepository.countWithdrawalBlockingLectures(BUYER_ID)).isZero();
    }

    /** 지급 완료·구매 취소·반환으로 끝난 주문 */
    private void finishedOrders() {
        LectureOrder paidOut = confirmed(DUE_PURCHASE);
        LectureOrderFixture.payOut(paidOut, itemId(paidOut), NOW_AT.minusDays(1));
        LectureOrder canceled = purchased(RECENT_PURCHASE);
        LectureOrderFixture.cancel(canceled, itemId(canceled), RECENT_PURCHASE.plusHours(1));
        LectureOrder returned = confirmed(NOT_DUE_PURCHASE);
        fileObjection(returned, NOT_DUE_PURCHASE.plusDays(1));
        LectureOrderFixture.acceptAndReturn(returned, itemId(returned), INQUIRY_ID, ADMIN_ID,
                NOT_DUE_PURCHASE.plusDays(2));
    }

    private Map<LectureWithdrawalBlockCode, WithdrawalBlockTotal> buyer() {
        return lectureOrderRepository.summarizeBuyerWithdrawalBlocks(BUYER_ID, NOW_AT).stream()
                .collect(Collectors.toMap(WithdrawalBlockTotal::code, total -> total));
    }

    private WithdrawalBlockTotal seller(Long sellerUserId) {
        return lectureOrderRepository.summarizeSellerWithdrawalBlocks(sellerUserId, NOW_AT);
    }

    private LectureOrder purchased(LocalDateTime purchasedAt) {
        return lectureOrderRepository.save(LectureOrderFixture.order(BUYER_ID, List.of(lecture()), purchasedAt));
    }

    /** 구매 시각에 바로 구매 확정한 주문 */
    private LectureOrder confirmed(LocalDateTime purchasedAt) {
        LectureOrder order = purchased(purchasedAt);
        order.confirmItem(itemId(order), purchasedAt);
        return order;
    }

    /** 구매 확정하고 지급 예정 시각에 지급을 시작한 주문 */
    private LectureOrder payoutPending(LocalDateTime purchasedAt) {
        LectureOrder order = confirmed(purchasedAt);
        order.startPayoutItem(itemId(order), itemOf(order).getPayoutScheduledAt());
        return order;
    }

    private LectureHpTask payoutTask(LectureOrder order) {
        OrderItem item = itemOf(order);
        return lectureHpTaskRepository.save(LectureHpTask.create(LectureHpTaskPurpose.PAYOUT,
                "LECTURE-PAYOUT-" + item.getId(), order.getId(), item.getId(), item.getBuyerUserId(),
                item.getSellerUserId(), item.getPriceHp(), item.getPayoutScheduledAt()));
    }

    private static void fileObjection(LectureOrder order, LocalDateTime filedAt) {
        order.fileObjection(itemId(order), BUYER_ID, INQUIRY_ID, "강의 내용이 소개와 다릅니다.", filedAt);
    }

    private Lecture lecture() {
        return withId(onSaleLecture(), nextLectureId++);
    }

    private static OrderItem itemOf(LectureOrder order) {
        return order.getItems().getFirst();
    }

    private static Long itemId(LectureOrder order) {
        return itemOf(order).getId();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
