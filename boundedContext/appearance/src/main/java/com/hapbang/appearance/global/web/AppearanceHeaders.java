package com.hapbang.appearance.global.web;

public final class AppearanceHeaders {

    /**
     * 로그인(OAuth + Session)이 붙기 전까지 현재 회원 ID를 받는 임시 헤더. 채팅 모듈과 같은 이름을 쓴다.
     */
    // TODO(security): 세션 키가 정해지면 이 헤더를 없애고 세션의 회원 ID를 쓴다.
    //  클라이언트가 보낸 회원 ID를 그대로 믿으므로 운영에 그대로 내보내면 안 된다.
    public static final String USER_ID = "X-User-Id";

    private AppearanceHeaders() {
    }
}
