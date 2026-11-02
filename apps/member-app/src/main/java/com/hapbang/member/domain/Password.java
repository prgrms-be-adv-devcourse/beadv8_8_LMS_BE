package com.hapbang.member.domain;

import java.util.regex.Pattern;

/**
 * 비밀번호 규칙 (#499): 8~64자, 영문과 숫자를 각각 하나 이상 포함, 영문·숫자·키보드 특수문자(공백을 뺀 ASCII)만.
 * BCrypt는 앞 72바이트만 쓰므로 한글 등 여러 바이트 문자를 막아 64자가 72바이트를 넘지 않게 한다.
 * 원문은 검증만 하고 저장하지 않는다 (앞뒤 공백도 지우지 않는다: 입력 그대로가 비밀번호다)
 */
public final class Password {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 64;

    /** 공백(0x20)을 뺀 출력 가능한 ASCII (! 부터 ~ 까지) */
    private static final Pattern ALLOWED = Pattern.compile("[\\x21-\\x7E]+");
    private static final Pattern LETTER = Pattern.compile("[A-Za-z]");
    private static final Pattern DIGIT = Pattern.compile("[0-9]");

    private Password() {
    }

    /** 사용자가 입력한 새 비밀번호를 검증한다 (가입·변경) */
    public static void validate(String input) {
        String password = input == null ? "" : input;
        if (password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            throw new MemberException(MemberErrorCode.INVALID_PASSWORD_LENGTH);
        }
        if (!ALLOWED.matcher(password).matches()) {
            throw new MemberException(MemberErrorCode.INVALID_PASSWORD_CHARACTER);
        }
        if (!LETTER.matcher(password).find() || !DIGIT.matcher(password).find()) {
            throw new MemberException(MemberErrorCode.PASSWORD_LETTER_AND_DIGIT_REQUIRED);
        }
    }
}
