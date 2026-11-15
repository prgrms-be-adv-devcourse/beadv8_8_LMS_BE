package com.hapbang.coupon.domain.usage;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 예약 요청의 근거(계산 때 받은 세대·정책 버전·적용 순서·금액)를 다시 계산한 결과와 대조한다.
 * 결과를 오류 코드로 바꾸는 일은 호출자(예약 서비스)가 한다.
 * <p>
 * 다시 계산은 요청이 고른 쿠폰들로 {@link CouponCalculator#apply}를 부른 결과다. 그래서 두 쪽의 발급 쿠폰 집합은 같아야 하고,
 * 다르면 금액 근거 불일치로 본다. 발급 쿠폰 ID 오름차순으로 보고, 한 장 안에서는 세대 → 정책 버전 → 금액 순서로 먼저 걸린
 * 불일치를 돌려준다.
 */
public final class ReservationBasis {

    private ReservationBasis() {
    }

    /** 예약 요청이 보낸 쿠폰 한 장의 근거 */
    public record RequestedCoupon(long memberCouponId, long expectedGeneration, int policyVersion, int applyOrder,
                                  long discount, long platformAmount, long sellerAmount) {
    }

    /** 불일치 종류 */
    public enum Mismatch {
        /** 기대 세대가 지금 세대와 다르다 */
        GENERATION,
        /** 정책 버전이 바뀌었다 */
        POLICY_VERSION,
        /** 적용 순서·할인·부담 금액이 다르다(쿠폰 집합이 다른 경우 포함) */
        AMOUNT
    }

    /**
     * 대조 결과.
     *
     * @param mismatch       불일치 종류. 일치하면 {@code null}
     * @param memberCouponId 불일치한 발급 쿠폰 ID. 일치하면 {@code null}
     */
    public record Check(Mismatch mismatch, Long memberCouponId) {

        static final Check MATCHED = new Check(null, null);

        public boolean matched() {
            return mismatch == null;
        }
    }

    public static Check compare(List<RequestedCoupon> requested, ItemResult recomputed) {
        Map<Long, AppliedCoupon> applied = recomputed.coupons().stream()
                .collect(Collectors.toMap(AppliedCoupon::memberCouponId, Function.identity()));
        List<RequestedCoupon> sorted = requested.stream()
                .sorted(Comparator.comparingLong(RequestedCoupon::memberCouponId))
                .toList();
        for (RequestedCoupon request : sorted) {
            AppliedCoupon coupon = applied.remove(request.memberCouponId());
            if (coupon == null) {
                return new Check(Mismatch.AMOUNT, request.memberCouponId());
            }
            if (coupon.expectedGeneration() != request.expectedGeneration()) {
                return new Check(Mismatch.GENERATION, request.memberCouponId());
            }
            if (coupon.policyVersion() != request.policyVersion()) {
                return new Check(Mismatch.POLICY_VERSION, request.memberCouponId());
            }
            if (coupon.applyOrder() != request.applyOrder() || coupon.discount() != request.discount()
                    || coupon.platformAmount() != request.platformAmount()
                    || coupon.sellerAmount() != request.sellerAmount()) {
                return new Check(Mismatch.AMOUNT, request.memberCouponId());
            }
        }
        return applied.keySet().stream().min(Long::compare)
                .map(extra -> new Check(Mismatch.AMOUNT, extra))
                .orElse(Check.MATCHED);
    }
}
