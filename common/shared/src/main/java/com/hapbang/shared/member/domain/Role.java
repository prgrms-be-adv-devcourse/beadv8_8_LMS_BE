package com.hapbang.shared.member.domain;

/** 회원 역할 (회원당 1개) */
public enum Role {
    /** 유저: 가입하면 기본. 강의 구매, 채널 인증 가능 */
    USER,
    /** 인플루언서: 채널 승인을 받은 회원. 출연 요청·수락, 환전 가능, 강의 등록 불가 */
    INFLUENCER,
    /** 인증된 인플루언서: 승급 심사를 통과한 인플루언서. 강의 등록·판매 가능 */
    VERIFIED_INFLUENCER,
    /** 관리자: 채널 심사, 승급 심사, 문의 답변 */
    ADMIN;

    /** 인플루언서 이상 여부 (관리자 제외) */
    public boolean isInfluencerOrAbove() {
        return this == INFLUENCER || this == VERIFIED_INFLUENCER;
    }
}
