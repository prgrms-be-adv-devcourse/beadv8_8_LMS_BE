package com.hapbang.chat.in;

public final class ChatHeaders {

    /**
     * 로그인(OAuth + Session)이 붙기 전까지 현재 회원 ID를 받는 임시 헤더. REST 요청과 STOMP CONNECT에 쓴다.
     */
    // TODO(security): 로그인(OAuth + Session)이 붙으면 이 헤더를 없애고 SecurityContext의 회원 ID를 쓴다.
    //  클라이언트가 보낸 회원 ID를 그대로 믿으므로 운영에 그대로 내보내면 안 된다.
    public static final String USER_ID = "X-User-Id";

    private ChatHeaders() {
    }
}
