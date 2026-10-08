package com.hapbang.chat.domain;

/**
 * 일반 메시지와 알림 메시지를 구분한다. 출연 요청·모집 선정 알림 등은 추후 추가한다.
 */
// TODO(event): 출연 모듈의 이벤트 DTO(출연 요청, 모집 선정 등)가 shared에 생기면 리스너를 추가하고 알림 메시지 타입을 늘린다.
//  받는 사람: 출연 요청 알림은 요청받은 게스트, 모집 선정 알림은 선정된 게스트(신청자).
public enum ChatMessageType {
    /** 회원이 보낸 일반 메시지 */
    TEXT,
    /** 시스템이 남기는 안내 메시지 (예: 퇴장) */
    SYSTEM
}
