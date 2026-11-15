package com.hapbang.coupon.domain;

import java.time.Instant;

/**
 * 회원 쿠폰 조회의 표시 판정(받을 수 있는 쿠폰 목록에 넣는지, 회원이 볼 수 있는 상태인지, 매진·받음 표시).
 * 발급 접수의 판정 권위는 Redis Lua이고, 여기 값은 화면 표시용이다. 접수 결과와 잠깐 다를 수 있다.
 */
public final class CouponAvailabilityRule {

    private CouponAvailabilityRule() {
    }

    /**
     * 이 강의 화면의 "받을 수 있는 쿠폰" 목록에 넣는가: 발급 중(OPEN)이고 발급 기간 안(시작 ≤ now &lt; 종료, 발급 접수 Lua와 같다)이며,
     * 그 강의 대상이거나 모든 강의 대상이다. 매진·이미 받은 쿠폰도 넣는다(표시로 구분한다).
     */
    public static boolean isListedFor(Coupon coupon, long lectureId, Instant now) {
        if (coupon.getStatus() != CouponStatus.OPEN) {
            return false;
        }
        if (now.isBefore(coupon.getIssueStartAt()) || !now.isBefore(coupon.getIssueEndAt())) {
            return false;
        }
        return switch (coupon.getTargetType()) {
            case LECTURE -> coupon.getLectureId() != null && coupon.getLectureId() == lectureId;
            case ANY_LECTURE -> true;
        };
    }

    /** 회원이 상세를 볼 수 있는 상태인가: 발급을 연 적이 있는 OPEN·SUSPENDED·CLOSED. 신청·심사 단계(DRAFT·REVIEW·REJECTED)는 볼 수 없다 */
    public static boolean isVisibleToMember(CouponStatus status) {
        return switch (status) {
            case OPEN, SUSPENDED, CLOSED -> true;
            case DRAFT, REVIEW, REJECTED -> false;
        };
    }

    /**
     * 매진인가: Redis 남은 재고가 0 이하이거나 DB 확정 발급 수가 수량에 닿았다.
     *
     * @param redisStock Redis 남은 재고. 모르면(키 없음·Redis 장애) {@code null}이고 DB 발급 수만 본다
     */
    public static boolean isSoldOut(int quantity, int issuedCount, Long redisStock) {
        return issuedCount >= quantity || (redisStock != null && redisStock <= 0);
    }

    /**
     * 회원이 이미 받았는가: 발급 쿠폰이 DB에 있거나, Redis에 접수됐다(접수됐지만 아직 DB에 확정되기 전).
     *
     * @param acceptedInRedis Redis 접수 회원 집합에 있는가. Redis에 닿지 못했으면 {@code false}로 넘긴다
     */
    public static boolean isIssued(boolean hasMemberCoupon, boolean acceptedInRedis) {
        return hasMemberCoupon || acceptedInRedis;
    }
}
