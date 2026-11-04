package com.hapbang.member.in;

/**
 * 가입 인증 코드를 보냈다는 응답 (#557)
 *
 * @param expiresInSeconds    코드 유효 시간(초)
 * @param resendAfterSeconds  같은 이메일로 다시 받을 수 있을 때까지(초)
 */
public record EmailCodeResponse(long expiresInSeconds, long resendAfterSeconds) {
}
