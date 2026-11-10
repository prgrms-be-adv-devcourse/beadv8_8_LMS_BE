package com.hapbang.coupon.in;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.hapbang.coupon.app.dto.CouponSummaryView;
import com.hapbang.coupon.app.dto.CouponUsageView;
import com.hapbang.coupon.app.dto.IssueRequestResult;
import com.hapbang.coupon.app.dto.IssueStatusView;
import com.hapbang.coupon.app.dto.MemberCouponView;
import com.hapbang.coupon.domain.CouponUsageStatus;
import com.hapbang.coupon.domain.MemberCouponStatus;
import com.hapbang.coupon.global.response.RsData;
import com.hapbang.coupon.in.dto.CouponQuoteRequest;
import com.hapbang.coupon.in.dto.IssueRequestCreateRequest;
import com.hapbang.shared.coupon.api.CouponCalculateResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 회원 쿠폰 API Swagger 명세(쿠폰 조회·발급 접수·발급 요청 상태·보유 쿠폰·계산 미리보기). 매핑은 {@link ApiV1CouponController} */
@Tag(name = "쿠폰", description = "회원의 쿠폰 조회·발급 접수·발급 요청 상태 조회·보유 쿠폰 조회·사용 이력·주문 전 할인 계산 미리보기")
public interface ApiV1CouponSpec {

    @Operation(summary = "강의에 받을 수 있는 쿠폰",
            description = "USER·INFLUENCER·VERIFIED_INFLUENCER. 이 강의 대상 쿠폰과 모든 강의 대상 쿠폰 중 발급 중(OPEN)이고 발급 기간 안인 것. "
                    + "최신순, 최대 100건. 매진이면 soldOut=true, 이미 받았거나 접수했으면 issued=true로 함께 준다. "
                    + "매진·받음은 표시용이며 실제 발급 여부는 발급 접수 결과가 정한다.")
    RsData<List<CouponSummaryView>> issuableCoupons(Long memberId,
                                                    @Parameter(description = "쿠폰을 볼 강의 ID", required = true)
                                                    Long lectureId);

    @Operation(summary = "쿠폰 상세",
            description = "USER·INFLUENCER·VERIFIED_INFLUENCER. OPEN·SUSPENDED·CLOSED 쿠폰만 보인다. 없거나 그 밖의 상태면 404 COUPON_NOT_FOUND.")
    RsData<CouponSummaryView> coupon(Long memberId, Long couponId);

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
            description = "USER·INFLUENCER·VERIFIED_INFLUENCER. status: ISSUED(발급 확정), REJECTED(거절 확정, rejectReason에 사유. 없으면 null), "
                    + "PENDING(접수됨, 확정 전), UNKNOWN(발급 저장소 장애로 확인 못함, 잠시 후 다시 조회), "
                    + "NOT_FOUND(요청이 없거나 다른 회원·다른 쿠폰의 요청).")
    RsData<IssueStatusView> issueStatus(Long memberId, Long couponId, String issueRequestId);

    @Operation(summary = "내 쿠폰 목록",
            description = "USER·INFLUENCER·VERIFIED_INFLUENCER. 발급받은 쿠폰과 할인 조건. 각 항목의 couponType(SELLER·PLATFORM)은 "
                    + "결제 화면에서 판매자 칸·플랫폼 칸을 나누는 데 쓴다.")
    RsData<List<MemberCouponView>> myCoupons(Long memberId,
                                             @Parameter(description = "발급 쿠폰 상태(AVAILABLE·RESERVED·USED·EXPIRED). 없으면 전체")
                                             MemberCouponStatus status);

    @Operation(summary = "강의에 쓸 수 있는 내 쿠폰",
            description = "USER·INFLUENCER·VERIFIED_INFLUENCER. 이 강의에 지금 적용할 수 있는 발급 쿠폰(사용 가능 상태, 만료 전, 대상 일치). "
                    + "각 항목의 couponType(SELLER·PLATFORM)은 결제 화면에서 판매자 칸·플랫폼 칸을 나누는 데 쓴다. 플랫폼 재원이 꺼져 있는 "
                    + "동안(기본값)은 플랫폼 쿠폰을 넣지 않는다(내 쿠폰 목록에는 보인다).")
    RsData<List<MemberCouponView>> usableFor(Long memberId,
                                             @Parameter(description = "쿠폰을 적용할 강의 ID", required = true)
                                             Long lectureId);

    @Operation(summary = "내 쿠폰 사용 이력",
            description = "USER·INFLUENCER·VERIFIED_INFLUENCER. 내 발급 쿠폰을 어느 주문·강의에 썼고 지금 어떻게 됐는지. 로그인 회원의 사용 "
                    + "시도만 나온다. 예약 최신순(reservedAt 내림차순, 같으면 usageId 내림차순)으로 고정하며 sort는 무시한다. page는 0부터, size "
                    + "기본 20·최대 100(넘으면 100으로 자른다). 응답은 {content, page{size, number, totalElements, totalPages}}. "
                    + "상태 표시: RESERVED \"결제 진행 중\"(쿠폰이 이 주문에 잡혀 있음), PAID \"사용함\", RELEASED \"결제되지 않아 "
                    + "돌려받음\", CANCELED \"구매 취소로 돌려받음\". RELEASED·CANCELED이고 returnedTo=EXPIRED면 \"기한이 지나 만료됨\". "
                    + "returnedTo·returnedAt은 RELEASED면 해제 결과·시각, CANCELED면 복원 결과·시각, 그 밖에는 null이다. 같은 주문 항목에 "
                    + "해제 뒤 다시 예약하면 이력이 두 건이다. 표시는 status로만 정한다(늦게 온 결제·취소 완료는 시각만 남아 RELEASED "
                    + "행에도 paidAt·canceledAt이 있을 수 있다). status가 정해진 값이 아니면 400.")
    RsData<Page<CouponUsageView>> myUsages(Long memberId,
                                           @Parameter(description = "사용 상태(RESERVED·PAID·RELEASED·CANCELED). 없으면 전체")
                                           CouponUsageStatus status,
                                           @Parameter(description = "page(0부터)·size(기본 20, 최대 100). sort는 무시한다")
                                           Pageable pageable);

    @Operation(summary = "할인 계산 미리보기",
            description = "USER·INFLUENCER·VERIFIED_INFLUENCER. 주문하기 전에 고른 강의의 쿠폰 할인을 계산한다. 상태를 바꾸지 않는다(쿠폰을 "
                    + "잡아 두지 않는다). 강의 가격·판매자는 서버가 강의에서 다시 읽고, 계산은 주문 때와 같다. selections를 생략(null)하면 내 사용 "
                    + "가능 쿠폰으로 최대 할인을 추천하고(recommended=true, 끝까지 찾았으면 optimal=true), 빈 목록이면 쿠폰 없이, 값이 있으면 "
                    + "그 매핑만 검증해 계산한다. 응답은 강의별 원가격·할인(플랫폼·판매자 부담)·구매자 금액과 적용한 쿠폰(generation 포함), "
                    + "합계 할인(totalDiscount)·합계 구매자 금액(totalBuyerAmount)이다. "
                    + "강의 주문(POST /api/v1/lectures/orders)에 쿠폰을 쓸 때는 이 응답의 쿠폰 매핑을 coupons로, totalBuyerAmount를 "
                    + "expectedBuyerAmountHp로 보낸다. 주문 때 다시 계산한 금액이 다르면 주문이 409 ORDER_QUOTE_CHANGED라 다시 미리보기를 "
                    + "한다. 강의 수가 상한을 넘으면 400 COUPON_TOO_MANY_ITEMS, 같은 강의가 두 번이면 400 COUPON_DUPLICATE_LECTURE, 강의가 "
                    + "없으면 404 LECTURE_NOT_FOUND, 강의 조회가 안 되면 503. 고른 쿠폰을 쓸 수 없으면 409(COUPON_NOT_APPLICABLE·"
                    + "COUPON_NOT_USABLE·COUPON_STACKING_VIOLATION, memberCouponId 포함). 플랫폼 재원이 꺼져 있는 동안(기본값)은 추천에서 "
                    + "플랫폼 쿠폰을 빼고, 플랫폼 쿠폰을 고르면 409 COUPON_NOT_USABLE(detail PLATFORM_FUNDING_DISABLED)이다.")
    RsData<CouponCalculateResponse> quote(Long memberId, CouponQuoteRequest request);
}
