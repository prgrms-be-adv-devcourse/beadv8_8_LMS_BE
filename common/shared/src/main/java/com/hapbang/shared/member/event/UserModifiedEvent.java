package com.hapbang.shared.member.event;

import com.hapbang.shared.member.dto.UserDto;

/** 회원 정보 변경 이벤트 (닉네임·역할·상태). 다른 모듈은 이걸로 복제 회원 갱신 */
public record UserModifiedEvent(UserDto user) {
}
