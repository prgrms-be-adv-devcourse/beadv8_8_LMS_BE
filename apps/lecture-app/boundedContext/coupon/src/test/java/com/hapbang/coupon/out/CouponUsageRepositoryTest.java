package com.hapbang.coupon.out;

import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponType;
import com.hapbang.coupon.domain.CouponUsage;
import com.hapbang.coupon.domain.CouponUsageStatus;
import com.hapbang.coupon.domain.MemberCoupon;
import com.hapbang.coupon.domain.MemberCouponStatus;
import com.hapbang.coupon.domain.usage.AppliedCoupon;
import com.hapbang.coupon.domain.usage.UsageItem;
import com.hapbang.testsupport.RepositoryTest;

import jakarta.persistence.EntityManager;

@RepositoryTest
class CouponUsageRepositoryTest {

    private static final long ORDER_ID = 900L;
    private static final long ORDER_ITEM_ID = 901L;
    private static final long PRICE = 10_000L;
    private static final Instant EXPIRES_AT = NOW.plusSeconds(86_400);

    @Autowired
    private CouponUsageRepository couponUsageRepository;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private MemberCouponRepository memberCouponRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void 사용_시도를_저장하고_예약_작업으로_찾고_해제_사실을_남긴다() {
        MemberCoupon memberCoupon = saveMemberCoupon("req-1");
        couponUsageRepository.saveAndFlush(usage(memberCoupon, ORDER_ITEM_ID, 0, 1_000, 0));
        entityManager.clear();

        CouponUsage found = couponUsageRepository.findByReservationWorkIdOrderByOrderItemIdAscApplyOrderAsc("work-1")
                .getFirst();
        assertThat(found.getGeneration()).isEqualTo(1);
        assertThat(found.getStatus()).isEqualTo(CouponUsageStatus.RESERVED);
        found.release("release-1", "주문 실패", "comp-1", MemberCouponStatus.AVAILABLE, NOW);
        entityManager.flush();
        entityManager.clear();

        assertThat(couponUsageRepository.findByOrderIdAndReleaseWorkIdOrderByMemberCouponIdAsc(ORDER_ID, "release-1"))
                .singleElement().satisfies(released -> {
                    assertThat(released.getReleasedTo()).isEqualTo(MemberCouponStatus.AVAILABLE);
                    assertThat(released.getReleaseCompensationRef()).isEqualTo("comp-1");
                });
        assertThat(couponUsageRepository.findByMemberCouponIdAndGeneration(memberCoupon.getId(), 1)).isPresent();
    }

    @Test
    void 같은_발급_쿠폰의_같은_세대는_두_행이_될_수_없다() {
        MemberCoupon memberCoupon = saveMemberCoupon("req-1");
        couponUsageRepository.saveAndFlush(usage(memberCoupon, ORDER_ITEM_ID, 0, 1_000, 0));

        assertThatThrownBy(() -> couponUsageRepository.saveAndFlush(usage(memberCoupon, ORDER_ITEM_ID + 1, 0, 1_000, 0)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(CouponUsageRepository.MEMBER_COUPON_GENERATION_UNIQUE);
    }

    @Test
    void 같은_발급_쿠폰을_같은_주문_항목에_동시에_두_번_예약할_수_없다() {
        MemberCoupon memberCoupon = saveMemberCoupon("req-1");
        couponUsageRepository.saveAndFlush(usage(memberCoupon, ORDER_ITEM_ID, 0, 1_000, 0));

        assertThatThrownBy(() -> couponUsageRepository.saveAndFlush(usage(memberCoupon, ORDER_ITEM_ID, 1, 1_000, 0)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(CouponUsageRepository.RESERVED_ORDER_ITEM_MEMBER_COUPON_UNIQUE);
    }

    @Test
    void 해제한_사용_시도가_있어도_같은_주문_항목에_새_세대로_다시_예약할_수_있다() {
        MemberCoupon memberCoupon = saveMemberCoupon("req-1");
        CouponUsage first = couponUsageRepository.saveAndFlush(usage(memberCoupon, ORDER_ITEM_ID, 0, 1_000, 0));
        first.release("release-1", "주문 실패", null, MemberCouponStatus.AVAILABLE, NOW);
        entityManager.flush();

        couponUsageRepository.saveAndFlush(usage(memberCoupon, ORDER_ITEM_ID, 1, 1_000, 0));

        assertThat(couponUsageRepository.findAll()).extracting(CouponUsage::getGeneration)
                .containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void 판매자_쿠폰에_플랫폼_부담이_있으면_DB가_막는다() {
        MemberCoupon memberCoupon = saveMemberCoupon("req-1");

        // d = f + s는 맞지만 판매자 쿠폰인데 플랫폼이 부담한다
        assertThatThrownBy(() -> couponUsageRepository.saveAndFlush(usage(memberCoupon, ORDER_ITEM_ID, 0, 1_000, 1_000)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_coupon_usage_bearer");
    }

    @Test
    void 할인이_적용_직전_남은_금액을_넘으면_DB가_막는다() {
        MemberCoupon memberCoupon = saveMemberCoupon("req-1");
        AppliedCoupon tooLarge = new AppliedCoupon(memberCoupon.getId(), memberCoupon.getCouponId(), CouponType.SELLER,
                1, 500, 600, 0, 600, 0, 1);

        assertThatThrownBy(() -> couponUsageRepository.saveAndFlush(CouponUsage.reserve(MEMBER_ID, ORDER_ID,
                new UsageItem(ORDER_ITEM_ID, LECTURE_ID, SELLER_ID, PRICE), tooLarge, "work-1", NOW)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_coupon_usage_discount_range");
    }

    @Test
    void 결제_완료와_취소_완료_사실을_저장하고_주문_항목으로_사용_시도를_찾는다() {
        MemberCoupon paidCoupon = saveMemberCoupon("req-1");
        MemberCoupon reservedCoupon = saveMemberCoupon("req-2");
        CouponUsage paid = couponUsageRepository.save(usage(paidCoupon, ORDER_ITEM_ID, 0, 1_000, 0));
        CouponUsage canceled = couponUsageRepository.save(usage(reservedCoupon, ORDER_ITEM_ID, 0, 1_000, 0));
        couponUsageRepository.save(usage(paidCoupon, ORDER_ITEM_ID + 1, 1, 1_000, 0));
        paid.applyPaid("order-item-paid:901", NOW, MemberCouponStatus.RESERVED, 1, NOW);
        canceled.applyCancelCompleted("order-item-cancel-completed:901", NOW, MemberCouponStatus.RESERVED, 1, NOW,
                NOW);
        entityManager.flush();
        entityManager.clear();

        assertThat(couponUsageRepository.findByOrderItemIdOrderByMemberCouponIdAscGenerationAsc(ORDER_ITEM_ID))
                .extracting(CouponUsage::getMemberCouponId, CouponUsage::getStatus, CouponUsage::getPaidEventId,
                        CouponUsage::getCancelEventId, CouponUsage::getRestoredTo)
                .containsExactly(
                        tuple(paidCoupon.getId(), CouponUsageStatus.PAID, "order-item-paid:901", null, null),
                        tuple(reservedCoupon.getId(), CouponUsageStatus.CANCELED, null,
                                "order-item-cancel-completed:901", MemberCouponStatus.EXPIRED));
    }

    private MemberCoupon saveMemberCoupon(String requestId) {
        Long couponId = couponRepository.save(Coupon.createSeller(SELLER_ID, lectureRate().build(), NOW)).getId();
        return memberCouponRepository.save(MemberCoupon.issue(MEMBER_ID, couponId, requestId, NOW, EXPIRES_AT, NOW));
    }

    /** 판매자 쿠폰 한 장을 원가격에 적용한 사용 시도. {@code platformAmount}를 주면 판매자 부담에서 그만큼 옮긴다 */
    private static CouponUsage usage(MemberCoupon memberCoupon, long orderItemId, long expectedGeneration,
                                     long discount, long platformAmount) {
        AppliedCoupon applied = new AppliedCoupon(memberCoupon.getId(), memberCoupon.getCouponId(), CouponType.SELLER,
                1, PRICE, discount, platformAmount, discount - platformAmount, expectedGeneration, 1);
        return CouponUsage.reserve(MEMBER_ID, ORDER_ID, new UsageItem(orderItemId, LECTURE_ID, SELLER_ID, PRICE),
                applied, "work-1", NOW);
    }
}
