package com.hapbang.shared.member.event;

/**
 * 회원 로그아웃 이벤트. global 로그아웃이 HTTP 세션을 없애기 직전에 발행한다 (#242).
 * 채팅은 이 세션으로 맺은 웹소켓 연결을 닫는다.
 * <p>
 * 트랜잭션 밖에서 발행하므로 {@code @ApplicationModuleListener}가 아니라 {@code @EventListener}로 받는다.
 * 같은 서버 안에서만 전달된다.
 *
 * @param userId    로그아웃한 회원 ID
 * @param sessionId 끝나는 HTTP 세션 ID (Spring Session ID)
 */
public record UserLoggedOutEvent(Long userId, String sessionId) {
}
