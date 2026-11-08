package com.hapbang.coupon.domain;

/**
 * DB 확정 발급 수(coupon.issued_count)의 조건부 카운터 규칙. DB 확정 발급 수는 총수량을 넘지 않는다.
 * <p>
 * 소비자는 발급 확정 트랜잭션의 마지막에 "확정 발급 수 &lt; 총수량 ∧ 쿠폰 epoch = 이벤트 epoch"일 때만 1을 더하는 조건부 UPDATE를 하고,
 * 바뀐 행 수를 {@link #interpret}로 해석한다. 0행이면 올리지 못한 것이라(총량 초과 또는 그 사이 epoch 변경) 그 트랜잭션(발급 쿠폰·발급 결과)을
 * 모두 되돌린다. 어느 쪽인지는 소비자가 쿠폰을 다시 읽어 가른다(epoch가 바뀌었으면 격리, 그대로면 총량 초과 거절).
 * Redis 재고가 1차로 막으므로 정상 경로에서 0행은 나오지 않는다(Redis 재고가 실제보다 커진 장애의 최종 방어다).
 */
public final class IssueCountRule {

    private IssueCountRule() {
    }

    /** 지금 확정 발급 수에서 한 장 더 확정할 수 있는가(확정 발급 수 &lt; 총수량) */
    public static boolean canIncrement(int issuedCount, int quantity) {
        return issuedCount < quantity;
    }

    /**
     * 조건부 UPDATE가 바꾼 행 수를 해석한다.
     *
     * @throws IllegalStateException 0·1이 아니다(쿠폰 한 행만 바꾸는 UPDATE라 나올 수 없다)
     */
    public static Outcome interpret(int updatedRows) {
        return switch (updatedRows) {
            case 1 -> Outcome.INCREMENTED;
            case 0 -> Outcome.OVER_QUANTITY;
            default -> throw new IllegalStateException("확정 발급 수 UPDATE가 쿠폰 한 행이 아닌 행을 바꿨다 rows=" + updatedRows);
        };
    }

    /** 조건부 UPDATE 결과 */
    public enum Outcome {
        /** 1을 더했다. 발급을 확정한다 */
        INCREMENTED,
        /** 올리지 못했다(총량 초과 또는 epoch 변경). 발급을 되돌리고 소비자가 쿠폰을 다시 읽어 거절·격리를 가른다 */
        OVER_QUANTITY
    }
}
