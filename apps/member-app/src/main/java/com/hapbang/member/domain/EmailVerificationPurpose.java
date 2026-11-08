package com.hapbang.member.domain;

/**
 * 이메일 인증의 용도 (#557). 인증 코드와 인증 토큰은 용도마다 따로 저장해, 가입용 토큰으로 비밀번호를 재설정하거나 그 반대로 쓸 수 없다.
 * 발송 제한(1분 간격·IP·이메일·전체)은 용도와 상관없이 합쳐 센다
 */
public enum EmailVerificationPurpose {
    /** 이메일 가입 */
    SIGN_UP,
    /** 비밀번호 재설정 (비밀번호를 잊었을 때) */
    PASSWORD_RESET
}
