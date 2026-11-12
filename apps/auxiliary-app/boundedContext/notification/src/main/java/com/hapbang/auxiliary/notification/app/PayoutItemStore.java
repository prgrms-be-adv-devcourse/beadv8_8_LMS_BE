package com.hapbang.auxiliary.notification.app;

/** 하루 묶음에 더한 지급 기록({@code notification_payout_item}, ND-103). 같은 지급을 한 번만 더하는 데 쓴다 */
public interface PayoutItemStore {

    /** 이 지급을 이미 더했는지 */
    boolean exists(long settlementId);

    /**
     * 지급을 묶음에 더했다고 기록한다. 호출한 트랜잭션 안에서 쓰고, 실패하면 그 트랜잭션 전체가 되돌아간다(ND-103).
     *
     * @param notificationId 이 지급을 더한 묶음 알림
     */
    void record(DailyPayout payout, long notificationId);
}
