package com.hapbang.shared.member.event;

import org.springframework.modulith.events.Externalized;

import com.hapbang.shared.member.dto.UserDto;

/**
 * 회원 정보 변경 이벤트 (닉네임·역할·상태·콘텐츠 분야·자기소개). 다른 모듈은 이걸로 복제 회원 갱신.
 * 분야·자기소개만 바뀌어도 나가므로, 역할 변경(예: 승인)을 이걸로 알아내는 곳은 이전 역할과 비교한다.
 * member-app이 Kafka {@code member.events.v1} 토픽에 회원 ID를 key로 발행한다
 */
@Externalized("member.events.v1::#{#this.user().id()}")
public record UserModifiedEvent(UserDto user) {
}
