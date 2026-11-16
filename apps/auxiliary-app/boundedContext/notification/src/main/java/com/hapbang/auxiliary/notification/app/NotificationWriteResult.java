package com.hapbang.auxiliary.notification.app;

/**
 * 알림 저장 결과.
 *
 * @param outcome        결과
 * @param notificationId 만든 알림 ID. 만들지 않았으면 null
 */
public record NotificationWriteResult(Outcome outcome, Long notificationId) {

    public enum Outcome {
        /** 새로 만들었다 */
        CREATED,
        /** 이미 있는 하루 묶음에 지급을 더했다(되살림 포함, ND-032·ND-097) */
        UPDATED,
        /** 같은 사건의 알림이 이미 있어(밀려난 알림 포함) 만들지 않았다(ND-019) */
        DUPLICATE,
        /** 받는 사람이 탈퇴·정지했거나 관리자라 만들지 않았다(ND-018·ND-072·ND-003) */
        SKIPPED_RECEIVER
    }

    static NotificationWriteResult created(Long id) {
        return new NotificationWriteResult(Outcome.CREATED, id);
    }

    static NotificationWriteResult of(Outcome outcome) {
        return new NotificationWriteResult(outcome, null);
    }
}
