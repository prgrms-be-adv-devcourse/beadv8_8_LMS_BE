package com.hapbang.shared.member.event;

import com.hapbang.shared.member.dto.UserDto;

/** 회원 가입 이벤트 (다른 모듈은 이걸로 복제 회원 생성) */
public record UserJoinedEvent(UserDto user) {
}
