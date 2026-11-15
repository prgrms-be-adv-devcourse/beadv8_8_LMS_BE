package com.hapbang.auxiliary.chatbot.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** FAQ 분류(#715). 초기 FAQ 원본 문서의 분류 11개와 같다. 화면의 "FAQ 모아보기" 탭 순서다 */
@Getter
@RequiredArgsConstructor
public enum FaqCategory {

    ACCOUNT("회원가입·로그인"),
    INFLUENCER("인플루언서·채널 인증"),
    HP("HP 충전·환불"),
    SETTLEMENT("수익·환전"),
    APPEARANCE("출연 요청·모집"),
    APPEARANCE_CANCEL("출연 취소·확인"),
    LECTURE_BUY("강의 구매"),
    LECTURE_SELL("강의 판매"),
    CHAT("채팅"),
    SUPPORT("고객센터·이의 제기"),
    WITHDRAW("탈퇴");

    /** 화면에 보여 줄 이름 */
    private final String displayName;
}
