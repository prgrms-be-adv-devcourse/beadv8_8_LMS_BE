package com.hapbang.member.in;

/**
 * 가입 이메일 인증을 마쳤다는 응답 (#557). 가입 요청의 emailVerificationToken에 그대로 넣는다
 *
 * @param emailVerificationToken 인증 토큰 (이 이메일로 한 번 가입할 수 있음)
 * @param expiresInSeconds       토큰 유효 시간(초)
 */
public record EmailVerificationTokenResponse(String emailVerificationToken, long expiresInSeconds) {
}
