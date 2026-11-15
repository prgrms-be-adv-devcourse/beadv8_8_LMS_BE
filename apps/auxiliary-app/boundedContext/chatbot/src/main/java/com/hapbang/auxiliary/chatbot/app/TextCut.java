package com.hapbang.auxiliary.chatbot.app;

/**
 * 글을 글자 수 상한으로 자른다. 코드포인트로 센다: 요청 검사(정규식의 {@code .})와 같은 기준이고, 이모지 같은 서로게이트 쌍을
 * 반으로 자르지 않는다(짝 잃은 서로게이트는 임베딩·LLM 요청이나 JSON 응답에서 오류가 될 수 있다).
 */
final class TextCut {

    private TextCut() {
    }

    /** 앞에서부터 코드포인트 {@code max}개까지. 짧으면 그대로 */
    static String head(String text, int max) {
        if (text.codePointCount(0, text.length()) <= max) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, max));
    }
}
