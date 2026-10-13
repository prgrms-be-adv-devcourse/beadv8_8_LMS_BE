package com.hapbang.customersupport.app;

/** Facade의 {@code @PreAuthorize} 역할 조건 */
final class CustomerSupportRoles {

    /** 문의 작성·조회: 회원(유저·인플루언서·인증된 인플루언서). 관리자는 문의 답변 쪽을 쓴다 */
    static final String MEMBER = "hasAnyRole('USER', 'INFLUENCER', 'VERIFIED_INFLUENCER')";

    /** 관리자 API (문의 목록·상세, 답변 등록·수정·삭제) */
    static final String ADMIN = "hasRole('ADMIN')";

    private CustomerSupportRoles() {
    }
}
