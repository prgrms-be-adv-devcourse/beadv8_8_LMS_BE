package com.hapbang.auxiliary.chatbot.domain;

import java.util.regex.Pattern;

/**
 * 회원이 쓴 글(자기소개)에서 연락처를 지운다 (#717). LLM·임베딩에 넘기기 전에 이 한 곳만 거친다(인플루언서 추천 #719).
 * <ul>
 *     <li>이메일: {@code 이름@도메인.최상위}</li>
 *     <li>전화번호: 휴대폰(010 등)·지역번호(02, 031 등)·대표번호(1588 등), {@code +82}·하이픈·점·공백 구분 포함</li>
 * </ul>
 * 지운 자리에는 표시를 남겨 문장이 이어지게 한다. 닉네임·이메일 같은 회원 식별 정보는 애초에 사본에 없다.
 */
public final class PersonalInfoScrubber {

    static final String EMAIL_MARK = "[이메일]";
    static final String PHONE_MARK = "[전화번호]";

    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}");

    // 숫자에 붙은 경우(주문번호 등 긴 숫자의 일부)는 잡지 않는다
    private static final Pattern PHONE = Pattern.compile(
            "(?<![0-9])(?:"
                    // 휴대폰·지역번호: (+82 또는 0)으로 시작해 2~3자리 / 3~4자리 / 4자리
                    + "(?:\\+82[-.\\s]?|0)(?:1[016789]|2|[3-6][1-5]|70)[-.\\s]?[0-9]{3,4}[-.\\s]?[0-9]{4}"
                    // 대표번호: 15xx·16xx·18xx-xxxx
                    + "|1[568][0-9]{2}[-.\\s]?[0-9]{4}"
                    + ")(?![0-9])");

    private PersonalInfoScrubber() {
    }

    /** 이메일·전화번호를 표시로 바꾼다. null이면 null */
    public static String scrub(String text) {
        if (text == null) {
            return null;
        }
        String withoutEmail = EMAIL.matcher(text).replaceAll(EMAIL_MARK);
        return PHONE.matcher(withoutEmail).replaceAll(PHONE_MARK);
    }
}
