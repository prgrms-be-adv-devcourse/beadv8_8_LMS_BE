package com.hapbang.chat.domain;

/**
 * 회원 모듈의 역할을 채팅 모듈에 복제한 값.
 */
// TODO: global에서 ChatUserRole을 만들고 사용한다.
public enum UserRole {
    USER,
    INFLUENCER,
    VERIFIED_INFLUENCER,
    ADMIN;
}
