package com.hapbang.shared.member.event;

import com.hapbang.shared.member.dto.UserDto;

/**
 * 회원 가입 시 회원 모듈이 발행한다. 다른 모듈은 이 이벤트로 복제 회원을 만든다.
 */
public record UserJoinedEvent(UserDto user) {
}
