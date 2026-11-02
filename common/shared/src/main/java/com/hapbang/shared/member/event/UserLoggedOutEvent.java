package com.hapbang.shared.member.event;

import java.time.Instant;

import org.springframework.modulith.events.Externalized;

/**
 * 회원 로그아웃 이벤트 (#539). member-app이 refresh token을 폐기한 회원 ID로 Kafka {@code member.events.v1} 토픽에
 * 회원 ID를 key로 발행한다. 채팅은 이 이벤트로 그 회원의 웹소켓 연결을 닫을 수 있다 (chat 처리는 후속).
 * <p>
 * 발행은 아웃박스라 같은 이벤트가 나중에 다시 올 수 있다(최소 한 번 전송). 받는 쪽은 {@code loggedOutAt}보다 나중에 맺은
 * 연결은 닫지 않아, 다시 로그인한 뒤 늦게 온 옛 로그아웃 이벤트로 새 연결을 끊지 않는다.
 * 다른 소비 앱은 모르는 종류로 무시한다 (ADR 0001).
 *
 * @param userId      로그아웃한 회원 ID
 * @param sessionId   쓰지 않는다. 항상 null이다. 세션 인증 때(#242) 쓰던 칸으로, chat의 옛 리스너가 아직 이 칸을 읽어
 *                    남겨 둔다. chat 후속 작업 때 지운다
 * @param loggedOutAt 로그아웃한 시각
 */
@Externalized("member.events.v1::#{#this.userId()}")
public record UserLoggedOutEvent(Long userId, String sessionId, Instant loggedOutAt) {

    /** 세션 인증 때 모양 그대로 만든다 (다른 앱 테스트가 쓴다. {@code loggedOutAt}은 null) */
    public UserLoggedOutEvent(Long userId, String sessionId) {
        this(userId, sessionId, null);
    }

    /** 로그아웃한 회원 ID와 시각으로 만든다 ({@code sessionId}는 null) */
    public static UserLoggedOutEvent of(Long userId, Instant loggedOutAt) {
        return new UserLoggedOutEvent(userId, null, loggedOutAt);
    }
}
