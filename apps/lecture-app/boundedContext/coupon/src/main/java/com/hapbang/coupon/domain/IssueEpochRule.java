package com.hapbang.coupon.domain;

/**
 * 발급 epoch(세대) 판정. epoch는 쿠폰 DB {@code coupon.epoch}가 기준이고 Redis 유실 복구가 끝날 때(재개)만 하나 오른다.
 * <ul>
 *     <li>{@link #judge}: 발급 요청 이벤트·보상 요청에 실린 epoch를 쿠폰의 지금 epoch와 비교한다. 같으면 {@link Verdict#CURRENT},
 *     작으면 복구 전 세대의 늦은 전달인 {@link Verdict#STALE}, 크면 아직 활성화되지 않은 세대라 있을 수 없는 {@link Verdict#AHEAD}다.</li>
 *     <li>STALE은 이미 저장된 결과만 다시 돌려주거나 격리하고 새 재고·발급 수를 바꾸지 않는다. AHEAD는 처리하지 않고 격리한다.</li>
 *     <li>복구가 진행되는 동안(차단 ~ 재구축) 쿠폰 epoch는 이전 값 그대로라, 복구 경계 재발행이 이전 epoch로 보낸 요청은 CURRENT로 처리된다.
 *     이것이 "복구 중 처리할 이전 epoch의 허용 범위"다. 재개가 epoch를 올린 뒤에 온 이전 epoch 전달만 STALE이 된다.</li>
 * </ul>
 */
public final class IssueEpochRule {

    private IssueEpochRule() {
    }

    /** 이벤트·보상 요청의 epoch를 쿠폰의 지금 epoch와 비교한다 */
    public static Verdict judge(long requestEpoch, long couponEpoch) {
        if (requestEpoch == couponEpoch) {
            return Verdict.CURRENT;
        }
        return requestEpoch < couponEpoch ? Verdict.STALE : Verdict.AHEAD;
    }

    /** 이 epoch로 온 요청이 지금 재고·발급 수를 바꿀 수 있는가(지금 세대만) */
    public static boolean mayChangeStock(long requestEpoch, long couponEpoch) {
        return judge(requestEpoch, couponEpoch) == Verdict.CURRENT;
    }

    /** 복구가 활성화할 다음 epoch */
    public static long next(long currentEpoch) {
        if (currentEpoch < Coupon.INITIAL_EPOCH) {
            throw new IllegalArgumentException("epoch는 " + Coupon.INITIAL_EPOCH + " 이상이다 epoch=" + currentEpoch);
        }
        return Math.addExact(currentEpoch, 1L);
    }

    /** epoch 판정 결과 */
    public enum Verdict {
        /** 쿠폰의 지금 세대 */
        CURRENT,
        /** 복구 전 세대의 늦은 전달. 기존 결과만 재생하거나 격리하고 재고를 바꾸지 않는다 */
        STALE,
        /** 아직 활성화되지 않은 세대. 정상 경로에서는 없다 */
        AHEAD
    }
}
