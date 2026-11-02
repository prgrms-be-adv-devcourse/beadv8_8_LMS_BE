package com.hapbang.shared.member.event;

import java.time.Instant;

import org.springframework.modulith.events.Externalized;

/**
 * 회원 로그아웃 이벤트 (#539). member-app이 refresh token을 폐기한 회원 ID로 Kafka {@code member.events.v1} 토픽에
 * 회원 ID를 key로 발행한다. 채팅은 이 이벤트로 그 회원의 모든 웹소켓 연결을 닫는다 (#501, 설계 문서 Q8).
 * <p>
 * 발행은 아웃박스라 같은 이벤트가 나중에 다시 올 수 있다(최소 한 번 전송). 받는 쪽은 {@code loggedOutAt}으로
 * 다시 로그인한 뒤 늦게 온 옛 로그아웃 이벤트를 가려낼 수 있다. 다른 소비 앱은 모르는 종류로 무시한다 (ADR 0001).
 *
 * @param userId      로그아웃한 회원 ID
 * @param loggedOutAt 로그아웃한 시각
 */
@Externalized("member.events.v1::#{#this.userId()}")
public record UserLoggedOutEvent(Long userId, Instant loggedOutAt) {
}
