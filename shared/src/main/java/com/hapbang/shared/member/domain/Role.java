package com.hapbang.shared.member.domain;

/** 회원 역할 (회원당 1개) */
public enum Role {
    USER,
    INFLUENCER,
    VERIFIED_INFLUENCER,
    ADMIN;

    /** 인플루언서 이상 여부 (관리자 제외) */
    public boolean isInfluencerOrAbove() {
        return this == INFLUENCER || this == VERIFIED_INFLUENCER;
    }
}
