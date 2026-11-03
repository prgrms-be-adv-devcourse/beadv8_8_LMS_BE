package com.hapbang.billing.global.scheduling;

/**
 * 한 번에 한 곳에서만 실행해야 하는 지갑 작업과 그 advisory lock 번호 (#598, BD-138).
 * <p>
 * 번호는 billing database 안에서만 겹치지 않으면 된다(advisory lock은 database마다 따로다). 한 번 정한 번호는 바꾸지 않고,
 * 새 작업은 다음 번호를 쓴다. 번호가 겹치지 않는지는 테스트가 확인한다.
 */
public enum BillingJob {

    /** 충전 결과 확인 배치 (#224) */
    CHARGE_RECOVERY(598_001L, "charge-recovery"),
    /** 환불 결과 확인 배치 (#260) */
    REFUND_RECOVERY(598_002L, "refund-recovery"),
    /** 환전 송금 결과 확인 배치 (#305) */
    EXCHANGE_PAYOUT_RECOVERY(598_003L, "exchange-payout-recovery"),
    /** 환전 정산일 자동 송금 배치 (#305) */
    EXCHANGE_SETTLEMENT(598_004L, "exchange-settlement"),
    /** 지갑 이벤트 재발행 배치 (#497) */
    EVENT_REPUBLISH(598_005L, "event-republish"),
    /** 관리자 회원 Backfill (#324) */
    MEMBER_BACKFILL(598_006L, "member-backfill");

    private final long lockKey;
    private final String tag;

    BillingJob(long lockKey, String tag) {
        this.lockKey = lockKey;
        this.tag = tag;
    }

    /** {@code pg_try_advisory_lock}에 넘기는 번호 */
    public long lockKey() {
        return lockKey;
    }

    /** 로그·지표 태그에 쓰는 이름 */
    public String tag() {
        return tag;
    }
}
