package com.hapbang.chat.app;

/**
 * Facade의 {@code @PreAuthorize} 역할 조건. 로그인 세션의 역할로 먼저 막고, UseCase가 채팅 회원({@code ChatUser})의
 * 복제된 역할로 다시 확인한다(로그인 이후 강등·정지 대비).
 */
final class ChatRoles {

    /** 채팅: 인플루언서 이상 또는 관리자 */
    static final String CHAT = "hasAnyRole('INFLUENCER', 'VERIFIED_INFLUENCER', 'ADMIN')";

    /** 관리자 API */
    static final String ADMIN = "hasRole('ADMIN')";

    private ChatRoles() {
    }
}
