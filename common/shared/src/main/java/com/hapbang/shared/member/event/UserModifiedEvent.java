package com.hapbang.shared.member.event;

import org.springframework.modulith.events.Externalized;

import com.hapbang.shared.member.dto.UserDto;

/**
 * 회원 정보 변경 이벤트 (닉네임·역할·상태). 다른 모듈은 이걸로 복제 회원 갱신.
 * member-app이 Kafka {@code member.events.v1} 토픽에 회원 ID를 key로 발행한다
 */
@Externalized("member.events.v1::#{#this.user().id()}")
public record UserModifiedEvent(UserDto user) {
}
