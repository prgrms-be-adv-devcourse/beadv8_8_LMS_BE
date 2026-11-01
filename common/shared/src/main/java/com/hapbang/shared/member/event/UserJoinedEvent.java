package com.hapbang.shared.member.event;

import org.springframework.modulith.events.Externalized;

import com.hapbang.shared.member.dto.UserDto;

/**
 * 회원 가입 이벤트 (다른 모듈은 이걸로 복제 회원 생성).
 * member-app이 Kafka {@code member.events.v1} 토픽에 회원 ID를 key로 발행한다
 */
@Externalized("member.events.v1::#{#this.user().id()}")
public record UserJoinedEvent(UserDto user) {
}
