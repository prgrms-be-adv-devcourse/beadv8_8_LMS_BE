package com.hapbang.chat.domain;

/**
 * 알림 메시지({@link ChatMessageType#NOTIFICATION})의 종류. {@code referenceId}가 가리키는 출연 건의 종류도 정한다.
 */
public enum NotificationKind {
    /** 출연 요청 도착 (게스트가 받는다). referenceId = 출연 요청서 ID */
    APPEARANCE_REQUEST_SENT,
    /** 출연 모집 지원 도착 (호스트가 받는다). referenceId = 지원 ID */
    APPEARANCE_APPLICATION_SUBMITTED
}
