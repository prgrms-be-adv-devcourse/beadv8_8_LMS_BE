package com.hapbang.coupon.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 소비자가 발급 요청을 거절로 확정한 사유. {@code coupon_issue_request.reject_reason}에 메시지를 남긴다 */
@Getter
@RequiredArgsConstructor
public enum IssueRejectReason {

    /** 확정 발급 수가 이미 총수량이다(조건부 카운터 0행) */
    OVER_QUANTITY("총수량을 넘어 발급하지 않았습니다."),
    /** 같은 회원이 다른 발급 요청으로 이미 이 쿠폰을 받았다(UNIQUE(member_id, coupon_id)) */
    MEMBER_ALREADY_ISSUED("이미 다른 요청으로 발급받은 회원입니다.");

    private final String message;
}
