package com.hapbang.lecture.domain;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주문 항목에 쓴 쿠폰 한 장의 계산 근거. 쿠폰 계산 응답을 그대로 남기고, 예약 결과를 받으면 예약 generation을 더한다.
 * <ul>
 *     <li>할인은 플랫폼 부담분 + 판매자 부담분이고, 두 몫은 0 이상, 할인은 적용 직전 남은 금액 이하다.</li>
 *     <li>발급 쿠폰·쿠폰 정책은 쿠폰 모듈의 ID로만 갖는다.</li>
 *     <li>삭제하지 않는다.</li>
 * </ul>
 */
@Entity
@Table(name = "order_item_coupon", schema = "lectures",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_order_item_coupon_item_member_coupon",
                        columnNames = {"order_item_id", "member_coupon_id"}),
                @UniqueConstraint(name = "uk_order_item_coupon_item_apply_order",
                        columnNames = {"order_item_id", "apply_order"})
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItemCoupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderItemId;

    /** 발급 쿠폰 ID(쿠폰 모듈) */
    @Column(nullable = false)
    private Long memberCouponId;

    /** 쿠폰 정책 ID(쿠폰 모듈) */
    @Column(nullable = false)
    private Long couponId;

    /** 쿠폰 종류. 쿠폰 모듈이 준 값 그대로다 */
    @Column(nullable = false, length = 20)
    private String couponType;

    /** 항목 안 적용 순서 */
    @Column(nullable = false)
    private int applyOrder;

    /** 적용 직전 남은 금액 */
    @Column(nullable = false)
    private long baseAmountHp;

    @Column(nullable = false)
    private long discountHp;

    @Column(nullable = false)
    private long platformAmountHp;

    @Column(nullable = false)
    private long sellerAmountHp;

    /** 계산 때 읽은 generation. 예약 요청에 그대로 보낸다 */
    @Column(nullable = false)
    private long expectedGeneration;

    /** 예약으로 오른 generation. 예약 결과를 받기 전에는 없다 */
    private Long reservedGeneration;

    /** 계산 때 읽은 쿠폰 정책 버전 */
    @Column(nullable = false)
    private int policyVersion;

    private OrderItemCoupon(Long orderItemId, Long memberCouponId, Long couponId, String couponType, int applyOrder,
                            long baseAmountHp, long discountHp, long platformAmountHp, long sellerAmountHp,
                            long expectedGeneration, int policyVersion) {
        this.orderItemId = orderItemId;
        this.memberCouponId = memberCouponId;
        this.couponId = couponId;
        this.couponType = couponType;
        this.applyOrder = applyOrder;
        this.baseAmountHp = baseAmountHp;
        this.discountHp = discountHp;
        this.platformAmountHp = platformAmountHp;
        this.sellerAmountHp = sellerAmountHp;
        this.expectedGeneration = expectedGeneration;
        this.policyVersion = policyVersion;
    }

    /** 쿠폰 계산 응답 한 장으로 만든다. 할인이 두 부담 몫의 합이 아니거나 남은 금액을 넘으면 거부한다 */
    public static OrderItemCoupon create(Long orderItemId, Long memberCouponId, Long couponId, String couponType,
                                         int applyOrder, long baseAmountHp, long discountHp, long platformAmountHp,
                                         long sellerAmountHp, long expectedGeneration, int policyVersion) {
        Objects.requireNonNull(orderItemId, "주문 항목 ID는 필수입니다.");
        Objects.requireNonNull(memberCouponId, "발급 쿠폰 ID는 필수입니다.");
        Objects.requireNonNull(couponId, "쿠폰 ID는 필수입니다.");
        Objects.requireNonNull(couponType, "쿠폰 종류는 필수입니다.");
        if (platformAmountHp < 0 || sellerAmountHp < 0) {
            throw new IllegalArgumentException("할인 부담 몫은 0 이상이어야 합니다. platform=" + platformAmountHp
                    + ", seller=" + sellerAmountHp);
        }
        if (discountHp != platformAmountHp + sellerAmountHp) {
            throw new IllegalArgumentException("할인이 부담 몫의 합과 다릅니다. discount=" + discountHp
                    + ", platform=" + platformAmountHp + ", seller=" + sellerAmountHp);
        }
        if (discountHp > baseAmountHp) {
            throw new IllegalArgumentException("할인이 남은 금액보다 큽니다. discount=" + discountHp
                    + ", base=" + baseAmountHp);
        }
        return new OrderItemCoupon(orderItemId, memberCouponId, couponId, couponType, applyOrder, baseAmountHp,
                discountHp, platformAmountHp, sellerAmountHp, expectedGeneration, policyVersion);
    }
}
