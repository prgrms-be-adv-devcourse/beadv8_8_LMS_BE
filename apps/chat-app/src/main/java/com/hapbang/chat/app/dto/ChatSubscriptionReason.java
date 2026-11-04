package com.hapbang.chat.app.dto;

/**
 * 방 topic 구독을 거절·회수한 사유. FE는 모르는 값도 재동기화로 처리한다(resync-frame.md).
 */
public enum ChatSubscriptionReason {

    /** 활성 참여가 아니다(거절) */
    NOT_MEMBER,
    /** 구독에 붙인 참여 세대가 현재 세대가 아니다(거절·회수) */
    STALE_PARTICIPATION,
    /** 방이 없거나 종료됐다(거절) */
    ROOM_NOT_FOUND,
    /** 채팅할 수 없는 회원이다(거절) */
    CHAT_NOT_ALLOWED,
    /** 한 연결의 방 구독 수 상한을 넘었다(거절) */
    TOO_MANY_SUBSCRIPTIONS,
    /** 앱이 구독하는 방 채널 수 상한을 넘었다(거절) */
    SERVER_BUSY,
    /** 그 회원이 방을 나갔다(회수) */
    LEFT,
    /** 그 회원이 방에서 내보내졌다(회수) */
    REMOVED,
    /** 방이 닫혔다(회수) */
    ROOM_CLOSED
}
