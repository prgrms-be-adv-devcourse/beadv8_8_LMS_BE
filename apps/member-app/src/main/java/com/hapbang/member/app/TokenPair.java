package com.hapbang.member.app;

/**
 * 발급한 토큰 쌍 (#370). access token은 응답 본문으로, refresh token은 HttpOnly 쿠키로만 내려준다.
 * refresh token은 원문이라 저장하지 않는다 (저장소에는 해시만 들어간다).
 * 유예 시간 안의 재발급(이미 회전된 토큰)은 refresh token을 새로 만들지 않아 null이고, 쿠키를 바꾸지 않는다
 */
public record TokenPair(String accessToken, String refreshToken) {

    @Override
    public String toString() {
        // 로그 등에 토큰이 찍히지 않게 한다
        return "TokenPair[****]";
    }
}
