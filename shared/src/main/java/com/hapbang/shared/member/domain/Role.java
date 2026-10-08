package com.hapbang.shared.member.domain;

/**
 * 회원 역할. 회원 1명당 1개를 가진다.
 * 다른 모듈은 복제 회원의 역할로 권한을 판단한다.
 */
public enum Role {
    USER,
    INFLUENCER,
    VERIFIED_INFLUENCER,
    ADMIN;

    /**
     * 인플루언서 이상(인플루언서, 인증된 인플루언서)인지 확인한다.
     * 관리자는 충전·구매·출연을 할 수 없으므로 포함하지 않는다.
     */
    public boolean isInfluencerOrAbove() {
        return this == INFLUENCER || this == VERIFIED_INFLUENCER;
    }
}
