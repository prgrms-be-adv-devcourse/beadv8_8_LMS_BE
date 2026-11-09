package com.hapbang.auxiliary.chatbot.app;

/** Facade의 {@code @PreAuthorize} 역할 조건 */
final class ChatbotRoles {

    /** 챗봇: 로그인 회원 전체(USER 이상, 11/06 결정). 비회원은 SecurityConfig가 먼저 401로 막는다 */
    static final String MEMBER = "hasAnyRole('USER', 'INFLUENCER', 'VERIFIED_INFLUENCER', 'ADMIN')";

    private ChatbotRoles() {
    }
}
