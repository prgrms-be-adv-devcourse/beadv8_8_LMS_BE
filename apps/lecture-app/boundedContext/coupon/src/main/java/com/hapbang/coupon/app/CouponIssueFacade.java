package com.hapbang.coupon.app;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import com.hapbang.coupon.app.dto.IssueRequestResult;
import com.hapbang.coupon.app.dto.IssueStatusView;
import com.hapbang.coupon.app.dto.MemberCouponView;
import com.hapbang.coupon.domain.MemberCouponStatus;

import lombok.RequiredArgsConstructor;

/** 회원의 발급 접수·발급 요청 상태·보유 쿠폰 조회 진입점 */
@Service
@PreAuthorize("hasAnyRole('USER','INFLUENCER','VERIFIED_INFLUENCER')")
@RequiredArgsConstructor
public class CouponIssueFacade {

    private final CouponIssueRequestUseCase couponIssueRequestUseCase;
    private final CouponIssueStatusQuery couponIssueStatusQuery;
    private final MemberCouponQuery memberCouponQuery;

    /** 접수(ACCEPTED)와 같은 요청 재응답(ALREADY_ACCEPTED)만 돌려주고, 나머지 결과는 {@code CouponException}이다 */
    public IssueRequestResult requestIssue(long couponId, long memberId, String issueRequestId) {
        return couponIssueRequestUseCase.requestIssue(couponId, memberId, issueRequestId);
    }

    public IssueStatusView issueStatus(long couponId, long memberId, String issueRequestId) {
        return couponIssueStatusQuery.issueStatus(couponId, memberId, issueRequestId);
    }

    /** @param status 없으면 전체 */
    public List<MemberCouponView> myCoupons(long memberId, MemberCouponStatus status) {
        return memberCouponQuery.myCoupons(memberId, status);
    }

    public List<MemberCouponView> usableFor(long memberId, long lectureId) {
        return memberCouponQuery.usableFor(memberId, lectureId);
    }
}
