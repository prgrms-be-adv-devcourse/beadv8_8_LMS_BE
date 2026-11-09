package com.hapbang.auxiliary.notification.app;

/**
 * 회원 이벤트 하나를 복제본에 반영한 결과(로그용).
 *
 * @param userId        회원 ID
 * @param outcome       반영 결과
 * @param memberVersion 반영 뒤 복제본의 회원 원본 버전
 */
public record NotificationUserSyncResult(Long userId, Outcome outcome, Long memberVersion) {

    public enum Outcome {
        /** 가입으로 새로 만들었다 */
        CREATED,
        /** 이미 있어 가입을 건너뛰었다(같은 가입 이벤트의 재처리) */
        SKIPPED_EXISTING,
        /** 더 큰 버전의 변경을 반영했다 */
        UPDATED,
        /** 이미 반영한 버전보다 크지 않은 변경이라 건너뛰었다 */
        SKIPPED_STALE
    }
}
