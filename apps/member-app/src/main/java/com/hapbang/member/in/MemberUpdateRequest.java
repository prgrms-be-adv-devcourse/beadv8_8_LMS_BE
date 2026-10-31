package com.hapbang.member.in;

/** 내 정보 수정 요청. 보내지 않은 값(null)은 바꾸지 않는다 */
public record MemberUpdateRequest(String nickname) {
}
