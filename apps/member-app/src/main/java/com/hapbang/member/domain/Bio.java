package com.hapbang.member.domain;

/** 자기소개 규칙 (#711): 앞뒤 공백을 지우고 0~100자(String.length, 닉네임과 같은 기준). 지운 뒤 비었으면 없음(null) */
public final class Bio {

    public static final int MAX_LENGTH = 100;

    private Bio() {
    }

    /** 사용자가 입력한 자기소개 검증. 앞뒤 공백을 지운 값을 돌려주고, null이거나 비었으면 null */
    public static String validate(String input) {
        if (input == null) {
            return null;
        }
        String bio = input.strip();
        if (bio.length() > MAX_LENGTH) {
            throw new MemberException(MemberErrorCode.INVALID_BIO_LENGTH);
        }
        return bio.isEmpty() ? null : bio;
    }
}
