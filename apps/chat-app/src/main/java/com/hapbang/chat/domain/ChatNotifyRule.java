package com.hapbang.chat.domain;

/**
 * 전달 작업의 새 메시지 알림 대상 규칙. 수신자는 불변 수신자 집합으로 정하고, 알림 대상은 그 수신자 안에서 고른다.
 */
public enum ChatNotifyRule {

    /** 알림을 보내지 않는다 (퇴장·재입장 안내) */
    NONE,

    /** 수신자 중 보낸 사람을 뺀 회원에게 알린다 (일반 메시지·출연 알림) */
    RECIPIENTS_EXCEPT_SENDER
}
