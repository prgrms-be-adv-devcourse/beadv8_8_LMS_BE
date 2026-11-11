package com.hapbang.coupon.in;

import java.util.List;

import com.hapbang.coupon.app.dto.IssueRequestResult;
import com.hapbang.coupon.app.dto.IssueStatusView;
import com.hapbang.coupon.app.dto.MemberCouponView;
import com.hapbang.coupon.domain.MemberCouponStatus;
import com.hapbang.coupon.global.response.RsData;
import com.hapbang.coupon.in.dto.IssueRequestCreateRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 회원 쿠폰 API Swagger 명세(발급 접수·발급 요청 상태·보유 쿠폰). 매핑은 {@link ApiV1CouponController} */
@Tag(name = "쿠폰", description = "회원의 쿠폰 발급 접수·발급 요청 상태 조회·보유 쿠폰 조회")
public interface ApiV1CouponSpec {

    @Operation(summary = "발급 접수",
            description = "USER·INFLUENCER·VERIFIED_INFLUENCER. 접수는 발급 완료가 아니다(202). 결과는 발급 요청 상태 조회로 확인한다. "
                    + "data.result는 ACCEPTED(새로 접수) 또는 ALREADY_ACCEPTED(같은 요청 ID 재전송, 기록을 바꾸지 않음)다. "
                    + "data.expiresAt은 발급될 쿠폰의 만료 시각(밀리초)이다. 재전송이면 최초 접수 때 정한 값이다. "
                    + "그 밖의 결과는 오류다: 매진 409 COUPON_SOLD_OUT, 이미 받음 409 COUPON_ALREADY_ISSUED, "
                    + "열리지 않음(없는 쿠폰·준비 전 포함) 409 COUPON_ISSUE_NOT_OPEN, 기간 아님 409 COUPON_ISSUE_NOT_IN_PERIOD, "
                    + "다른 회원이 쓴 요청 ID 409 ISSUE_REQUEST_ID_CONFLICT, 발급 저장소 장애 503. "
                    + "발급이 중단된 뒤에는 같은 요청 ID를 다시 보내도 409 COUPON_ISSUE_NOT_OPEN이므로 상태 조회로 확인한다.")
    RsData<IssueRequestResult> requestIssue(Long memberId, Long couponId, IssueRequestCreateRequest request);

    @Operation(summary = "발급 요청 상태",
            description = "USER·INFLUENCER·VERIFIED_INFLUENCER. status: ISSUED(발급 확정), REJECTED(거절 확정), "
                    + "PENDING(접수됨, 확정 전), UNKNOWN(발급 저장소 장애로 확인 못함, 잠시 후 다시 조회), "
                    + "NOT_FOUND(요청이 없거나 다른 회원·다른 쿠폰의 요청).")
    RsData<IssueStatusView> issueStatus(Long memberId, Long couponId, String issueRequestId);

    @Operation(summary = "내 쿠폰 목록", description = "USER·INFLUENCER·VERIFIED_INFLUENCER. 발급받은 쿠폰과 할인 조건.")
    RsData<List<MemberCouponView>> myCoupons(Long memberId,
                                             @Parameter(description = "발급 쿠폰 상태(AVAILABLE·RESERVED·USED·EXPIRED). 없으면 전체")
                                             MemberCouponStatus status);

    @Operation(summary = "강의에 쓸 수 있는 내 쿠폰",
            description = "USER·INFLUENCER·VERIFIED_INFLUENCER. 이 강의에 지금 적용할 수 있는 발급 쿠폰(사용 가능 상태, 만료 전, 대상 일치).")
    RsData<List<MemberCouponView>> usableFor(Long memberId,
                                             @Parameter(description = "쿠폰을 적용할 강의 ID", required = true)
                                             Long lectureId);
}
