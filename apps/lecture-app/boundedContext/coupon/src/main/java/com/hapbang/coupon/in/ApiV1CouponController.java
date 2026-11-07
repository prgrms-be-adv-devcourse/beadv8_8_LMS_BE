package com.hapbang.coupon.in;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.coupon.app.CouponIssueFacade;
import com.hapbang.coupon.app.dto.IssueRequestResult;
import com.hapbang.coupon.app.dto.IssueStatusView;
import com.hapbang.coupon.app.dto.MemberCouponView;
import com.hapbang.coupon.domain.MemberCouponStatus;
import com.hapbang.coupon.global.auth.LoginUserId;
import com.hapbang.coupon.global.response.RsData;
import com.hapbang.coupon.in.dto.IssueRequestCreateRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 회원 쿠폰 API. 역할 검사는 {@link CouponIssueFacade}가 한다. Swagger 명세는 {@link ApiV1CouponSpec} */
// 성공은 쿠폰 RsData로 감싸고, 쿠폰 업무 오류는 CouponExceptionHandler, 요청 값 오류·권한 거절은 강의 공통 핸들러가 응답한다
@RestController
@RequestMapping("/api/v1/coupons")
@RequiredArgsConstructor
public class ApiV1CouponController implements ApiV1CouponSpec {

    private final CouponIssueFacade couponIssueFacade;

    @Override
    @PostMapping("/{couponId}/issue-requests")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RsData<IssueRequestResult> requestIssue(@LoginUserId Long memberId,
                                                   @PathVariable("couponId") Long couponId,
                                                   @Valid @RequestBody IssueRequestCreateRequest request) {
        IssueRequestResult result = couponIssueFacade.requestIssue(couponId, memberId, request.issueRequestId());
        return RsData.of(HttpStatus.ACCEPTED, "쿠폰 발급을 접수했습니다.", result);
    }

    @Override
    @GetMapping("/{couponId}/issue-requests/{issueRequestId}")
    public RsData<IssueStatusView> issueStatus(@LoginUserId Long memberId,
                                               @PathVariable("couponId") Long couponId,
                                               @PathVariable("issueRequestId") String issueRequestId) {
        return RsData.ok("발급 요청 상태를 조회했습니다.",
                couponIssueFacade.issueStatus(couponId, memberId, issueRequestId));
    }

    @Override
    @GetMapping("/me")
    public RsData<List<MemberCouponView>> myCoupons(@LoginUserId Long memberId,
                                                    @RequestParam(name = "status", required = false)
                                                    MemberCouponStatus status) {
        return RsData.ok("내 쿠폰을 조회했습니다.", couponIssueFacade.myCoupons(memberId, status));
    }

    @Override
    @GetMapping("/me/usable")
    public RsData<List<MemberCouponView>> usableFor(@LoginUserId Long memberId,
                                                    @RequestParam(name = "lectureId") Long lectureId) {
        return RsData.ok("강의에 쓸 수 있는 쿠폰을 조회했습니다.", couponIssueFacade.usableFor(memberId, lectureId));
    }
}
