package com.hapbang.auxiliary.chatbot.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** DLT로 보낸 회원 이벤트의 종류 (#976) */
@Getter
@RequiredArgsConstructor
public enum MemberEventType {

    JOINED("회원 가입"),
    MODIFIED("회원 정보 변경"),
    MASKED("개인정보 마스킹(탈퇴 2단계)"),
    /** 값을 읽지 못한 메시지(형식 오류, 모르는 타입). 다시 처리할 수 없다 */
    UNREADABLE("읽지 못한 메시지");

    private final String description;
}
