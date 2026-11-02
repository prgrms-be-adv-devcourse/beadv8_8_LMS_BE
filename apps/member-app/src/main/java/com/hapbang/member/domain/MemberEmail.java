package com.hapbang.member.domain;

import java.util.Locale;

/**
 * 비밀번호 가입·로그인의 이메일 (#499). 앞뒤 공백을 지우고 소문자로 맞춰, 대소문자만 다른 같은 이메일이 두 번 가입되지 않게 한다.
 * 형식 검사는 요청 DTO(@Email)가 한다
 */
public final class MemberEmail {

    private MemberEmail() {
    }

    public static String normalize(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }
}
