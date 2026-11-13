package com.hapbang.auxiliary.notification.app;

import com.hapbang.auxiliary.global.scheduling.JobLockKey;

/**
 * 한 번에 한 곳에서만 실행하는 알림 작업과 그 advisory lock 번호. auxiliary database를 챗봇·검색과 함께 쓰므로 번호는 그 작업을 더한
 * 이슈 번호 × 1000 + 순번이고, 한 번 정한 번호는 바꾸지 않는다({@link JobLockKey}).
 */
public enum NotificationJob implements JobLockKey {

    /** 회원 복제본 Backfill: 기동 때 자동 실행과 관리자 재실행(#859, ND-078·ND-079) */
    USER_BACKFILL(859_001L, "notification-user-backfill"),
    /** 예약 배치: 보낼 시각이 지난 예약 발송과 끝난 예약 정리(#928, ND-029·ND-142) */
    SCHEDULE_DISPATCH(928_001L, "notification-schedule-dispatch");

    private final long lockKey;
    private final String tag;

    NotificationJob(long lockKey, String tag) {
        this.lockKey = lockKey;
        this.tag = tag;
    }

    @Override
    public long lockKey() {
        return lockKey;
    }

    @Override
    public String tag() {
        return tag;
    }
}
