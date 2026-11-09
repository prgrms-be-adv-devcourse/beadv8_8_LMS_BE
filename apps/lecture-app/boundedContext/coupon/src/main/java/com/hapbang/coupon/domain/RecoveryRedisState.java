package com.hapbang.coupon.domain;

/**
 * 복구 중인 쿠폰의 Redis issue-control 상태. 재개 결과를 알 수 없는 복구(RESUMING)를 취소해도 되는지 판단하는 데 쓴다
 * ({@link RecoveryRun#abortLostResume}).
 */
public enum RecoveryRedisState {
    /** issue-control이 있고 그 runId가 지금 Redis 프로세스와 같다 */
    INTACT,
    /** issue-control이 없다(유실) */
    CONTROL_MISSING,
    /** issue-control의 runId가 없거나 지금 Redis 프로세스와 다르다(재시작·유실 의심) */
    PROCESS_CHANGED;

    /** 재구축한 Redis 기록을 더는 믿을 수 없다 */
    public boolean isLost() {
        return this != INTACT;
    }
}
