package com.hapbang.chat.app.dto;

/**
 * 재동기화 신호의 사유. FE는 모르는 값도 재동기화로 처리한다(resync-frame.md).
 */
public enum ChatResyncReason {

    /** 이 앱의 Redis 실시간 구독이 끊겼다가 다시 연결됨. 모든 로컬 연결에 보낸다 */
    REALTIME_RESUBSCRIBED,

    /** 이 앱의 실시간 처리 실행기가 넘쳐 메시지를 버림. 그 메시지의 로컬 수신자에게만 보낸다 */
    DELIVERY_DROPPED
}
