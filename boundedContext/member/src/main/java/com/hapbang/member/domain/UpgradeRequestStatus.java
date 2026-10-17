package com.hapbang.member.domain;

/** 승급 신청 상태 */
public enum UpgradeRequestStatus {
    /** 신청, 관리자 심사 대기 (회원당 1건) */
    PENDING,
    /** 승인 → 인증된 인플루언서 */
    APPROVED,
    /** 반려 (다시 신청 가능) */
    REJECTED
}
