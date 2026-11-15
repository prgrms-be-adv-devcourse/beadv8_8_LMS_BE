package com.hapbang.coupon.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * 발급 저장소(Redis) 용량 상한 규칙. 상한 값은 설정(측정 전 플레이스홀더)이 주고, 이 규칙은 비교만 한다.
 * <ul>
 *     <li>쿠폰별 상한: 쿠폰 한 개의 수량. 수량만큼 Redis에 회원·요청 기록이 생기므로 쿠폰 하나가 저장소를 다 쓰지 못하게 한다.</li>
 *     <li>합산 상한: Redis 기록이 남은 모든 쿠폰(발급 종료(CLOSED)됐어도 기록을 아직 정리하지 않은 쿠폰 포함)의 수량 합.
 *     발급을 열 때만 쿠폰 수량만큼 용량을 예약하고(발행·심사 요청은 예약하지 않는다), 대사 배치가 그 쿠폰의 기록을 모두 정리한 뒤 푼다.
 *     심사 거절은 접수를 연 적이 없어 거절 직후 푼다. 열기가 중간에 실패해 남은 예약은 고아로 보고 푼다({@link #orphan}).</li>
 * </ul>
 */
public final class IssueCapacityRule {

    private IssueCapacityRule() {
    }

    /** 쿠폰 수량이 쿠폰별 상한 이하인가 */
    public static boolean withinPerCoupon(long quantity, long maxQuantityPerCoupon) {
        return quantity <= maxQuantityPerCoupon;
    }

    /**
     * 이미 예약된 합(기록이 남은 쿠폰의 수량 합)에 이번 수량을 더해도 합산 상한 이하인가.
     * DB 조건부 UPDATE가 같은 식으로 원자적으로 판정하며, 이 메서드는 그 식의 정의다.
     */
    public static boolean fitsTotal(long reservedTotal, long quantity, long maxTotalQuantity) {
        return reservedTotal + quantity <= maxTotalQuantity;
    }

    /**
     * 쿠폰의 용량 예약을 풀어도 되는가: 다시 열 수 없는 상태(발급 종료 CLOSED, 심사 거절 REJECTED)이고 Redis에 정리할 기록이 하나도 남지
     * 않았으며(남은 requests 0), 진행 중 접수와 거절 보상 대기가 없다. 기록이 남은 CLOSED 쿠폰은 계속 용량을 차지한다.
     */
    public static boolean releasable(CouponStatus status, long requestsLeft, long inProgress, long pendingCompensation) {
        return terminal(status) && requestsLeft == 0 && inProgress == 0 && pendingCompensation == 0;
    }

    /**
     * 고아 예약인가: 쿠폰이 발급 중(OPEN·SUSPENDED)이 아니고 Redis에 그 쿠폰의 key가 하나도 없다. 발급 열기가 Redis 준비 전에 실패했거나
     * 정리가 key만 지우고 예약 해제 전에 멈춘 경우다. 다시 열 수 없는 쿠폰(CLOSED·REJECTED)은 바로 풀고, 열기 전 쿠폰(DRAFT·REVIEW)은 진행 중인
     * 열기일 수 있어 예약(또는 재사용) 뒤 대기 시간이 지나야 푼다({@link #orphanCutoff}).
     *
     * @param redisKeysExist issue-control·재고·매진 표시·회원·요청 기록 중 하나라도 있는가
     */
    public static boolean orphan(CouponStatus status, boolean redisKeysExist) {
        return status != CouponStatus.OPEN && status != CouponStatus.SUSPENDED && !redisKeysExist;
    }

    /**
     * 고아 예약을 풀 수 있는 예약 시각의 상한. 열기 전 쿠폰(DRAFT·REVIEW)은 {@code now − grace} 이전에 예약된 것만 풀고, 다시 열 수 없는
     * 쿠폰(CLOSED·REJECTED)은 기다리지 않는다(null).
     */
    public static Instant orphanCutoff(CouponStatus status, Instant now, Duration grace) {
        return terminal(status) ? null : now.minus(grace);
    }

    /** 다시 열 수 없는 상태(발급 종료·심사 거절)인가 */
    public static boolean terminal(CouponStatus status) {
        return status == CouponStatus.CLOSED || status == CouponStatus.REJECTED;
    }
}
