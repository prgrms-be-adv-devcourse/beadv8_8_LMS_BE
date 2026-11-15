package com.hapbang.auxiliary.search.influencer.app;

/** Facade의 {@code @PreAuthorize} 역할 조건 */
final class SearchRoles {

    /** 검색: 로그인 회원 전체(유저 이상, policy.md "인플루언서 목록·프로필 조회: 비회원 X"). 비회원은 SecurityConfig가 먼저 401로 막는다 */
    static final String MEMBER = "hasAnyRole('USER', 'INFLUENCER', 'VERIFIED_INFLUENCER', 'ADMIN')";

    private SearchRoles() {
    }
}
