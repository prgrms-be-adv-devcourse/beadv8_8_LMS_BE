package com.hapbang.member.domain;

/** 채널 인증 상태 */
public enum ChannelVerificationStatus {
    /** 등록만 (팔로워 기준 미달, 승인 요청 전) */
    REGISTERED,
    /** 승인 요청, 관리자 심사 대기 */
    PENDING,
    /** 승인 (자동 또는 관리자) */
    APPROVED,
    /** 반려 (다시 승인 요청 가능) */
    REJECTED
}
