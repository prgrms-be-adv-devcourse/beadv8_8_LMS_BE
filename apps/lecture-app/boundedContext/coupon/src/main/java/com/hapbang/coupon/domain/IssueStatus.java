package com.hapbang.coupon.domain;

/** 발급 요청 상태 조회 결과. 판정 규칙은 {@link IssueStatusRule}에 있다 */
public enum IssueStatus {
    /** DB에 발급 확정 */
    ISSUED,
    /** DB에 거절 확정 */
    REJECTED,
    /** Redis에 접수됐고 아직 DB에 확정되지 않음 */
    PENDING,
    /** DB에 결과가 없고 Redis 장애로 접수 여부를 확인하지 못함 */
    UNKNOWN,
    /** 요청이 없거나 다른 회원(다른 쿠폰)의 요청 */
    NOT_FOUND
}
