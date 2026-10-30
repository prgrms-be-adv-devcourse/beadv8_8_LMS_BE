package com.hapbang.chat.app;

/**
 * 회원이 지금 채팅할 수 있는지 판단한다. 웹소켓 연결 검사처럼 회원 Facade가 자격만 물을 때 쓰는 계약이다(서비스 경계 규칙).
 */
public interface ChatUserEligibility {

    /** 채팅 회원 복제본 기준으로 지금 채팅할 수 있는지. 복제본이 없으면 false */
    boolean canChat(Long userId);
}
