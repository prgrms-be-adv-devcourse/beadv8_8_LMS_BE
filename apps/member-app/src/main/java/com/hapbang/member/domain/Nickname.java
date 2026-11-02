package com.hapbang.member.domain;

import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/** 닉네임 규칙: 2~12자, 한글(완성형)·영문·숫자·공백·밑줄, 연속 공백 금지 (중복 허용) */
public final class Nickname {

    public static final int MIN_LENGTH = 2;
    public static final int MAX_LENGTH = 12;

    private static final Pattern ALLOWED = Pattern.compile("[가-힣A-Za-z0-9_ ]+");
    private static final Pattern NOT_ALLOWED = Pattern.compile("[^가-힣A-Za-z0-9_ ]");
    private static final Pattern SPACES = Pattern.compile(" {2,}");

    private Nickname() {
    }

    /** 사용자가 입력한 닉네임 검증. 앞뒤 공백은 지우고 돌려준다 */
    public static String validate(String input) {
        String nickname = input == null ? "" : input.strip();
        if (nickname.length() < MIN_LENGTH || nickname.length() > MAX_LENGTH) {
            throw new MemberException(MemberErrorCode.INVALID_NICKNAME_LENGTH);
        }
        if (!ALLOWED.matcher(nickname).matches()) {
            throw new MemberException(MemberErrorCode.INVALID_NICKNAME_CHARACTER);
        }
        if (SPACES.matcher(nickname).find()) {
            throw new MemberException(MemberErrorCode.INVALID_NICKNAME_SPACE);
        }
        return nickname;
    }

    /** 가입 시 구글 이름을 규칙에 맞게 정리한다. 남는 글자가 2자 미만이면 기본 닉네임 */
    public static String fromGoogleName(String name) {
        String cleaned = NOT_ALLOWED.matcher(name == null ? "" : name).replaceAll("");
        cleaned = SPACES.matcher(cleaned).replaceAll(" ").strip();
        if (cleaned.length() > MAX_LENGTH) {
            cleaned = cleaned.substring(0, MAX_LENGTH).strip();
        }
        return cleaned.length() >= MIN_LENGTH ? cleaned : defaultNickname();
    }

    /** 회원 + 숫자 4자리 (중복 허용이라 겹쳐도 된다) */
    private static String defaultNickname() {
        return "회원" + ThreadLocalRandom.current().nextInt(1000, 10000);
    }
}
