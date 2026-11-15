package com.hapbang.coupon.app;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import com.hapbang.coupon.app.dto.CouponSummaryView;
import com.hapbang.coupon.app.dto.CouponUsageView;
import com.hapbang.coupon.app.dto.IssueRequestResult;
import com.hapbang.coupon.app.dto.IssueStatusView;
import com.hapbang.coupon.app.dto.MemberCouponView;
import com.hapbang.coupon.domain.CouponUsageStatus;
import com.hapbang.coupon.domain.MemberCouponStatus;
import com.hapbang.coupon.domain.usage.Selection;
import com.hapbang.shared.coupon.api.CouponCalculateResponse;

import lombok.RequiredArgsConstructor;

/** 회원의 쿠폰 조회(받을 수 있는 쿠폰·상세)·발급 접수·발급 요청 상태·보유 쿠폰 조회·사용 이력·계산 미리보기 진입점 */
@Service
@PreAuthorize("hasAnyRole('USER','INFLUENCER','VERIFIED_INFLUENCER')")
@RequiredArgsConstructor
public class CouponIssueFacade {

    private final CouponIssueRequestUseCase couponIssueRequestUseCase;
    private final CouponIssueStatusQuery couponIssueStatusQuery;
    private final MemberCouponQuery memberCouponQuery;
    private final CouponSummaryQuery couponSummaryQuery;
    private final CouponCalculateUseCase couponCalculateUseCase;
    private final CouponUsageHistoryQuery couponUsageHistoryQuery;

    /** 이 강의에 지금 받을 수 있는 쿠폰(매진·받음 표시 포함) */
    public List<CouponSummaryView> issuableCoupons(long memberId, long lectureId) {
        return couponSummaryQuery.issuableFor(memberId, lectureId);
    }

    /** 쿠폰 상세. OPEN·SUSPENDED·CLOSED만 보이고 그 밖은 404다 */
    public CouponSummaryView coupon(long memberId, long couponId) {
        return couponSummaryQuery.summary(memberId, couponId);
    }

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

    /**
     * 내 쿠폰 사용 이력(예약 최신순 고정, 페이지 크기 상한 100).
     *
     * @param status 없으면 전체
     */
    public Page<CouponUsageView> myUsages(long memberId, CouponUsageStatus status, Pageable pageable) {
        return couponUsageHistoryQuery.myUsages(memberId, status, pageable);
    }

    /**
     * 계산 미리보기. 강의 가격·판매자는 강의 조회로 채우고 내부 계산과 같은 계산·응답이다. 상태를 바꾸지 않는다.
     *
     * @param selections {@code null}이면 추천, 빈 목록이면 쿠폰 없이, 값이 있으면 그 매핑만 검증·계산한다
     */
    public CouponCalculateResponse quote(long memberId, List<Long> lectureIds, List<Selection> selections) {
        return couponCalculateUseCase.quote(memberId, lectureIds, selections);
    }
}
