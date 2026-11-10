package com.hapbang.coupon.in;

import com.hapbang.coupon.global.response.RsData;
import com.hapbang.shared.coupon.api.CouponCalculateRequest;
import com.hapbang.shared.coupon.api.CouponCalculateResponse;
import com.hapbang.shared.coupon.api.CouponPriceRaiseBlockedResponse;
import com.hapbang.shared.coupon.api.CouponReleaseRequest;
import com.hapbang.shared.coupon.api.CouponReleaseResponse;
import com.hapbang.shared.coupon.api.CouponReservationStatusResponse;
import com.hapbang.shared.coupon.api.CouponReserveRequest;
import com.hapbang.shared.coupon.api.CouponReserveResponse;
import com.hapbang.shared.internal.InternalApiHeaders;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 쿠폰 사용 내부 API Swagger 명세. 매핑은 {@link ApiV1CouponInternalController}. 계약 문서는 docs/api/internal-coupon.md */
@Tag(name = "쿠폰 사용 (내부)", description = "모듈 간 내부 API. 강의가 주문할 때 쿠폰 할인 계산·예약·해제와 가격 인상 제한 확인에 쓴다. "
        + "회원 로그인으로는 호출할 수 없고, 내부 포트로만 받는다(외부 포트로 오면 404).")
public interface ApiV1CouponInternalSpec {

    String API_KEY_DESCRIPTION = "내부 API 키 (hapbang.internal-api.key). 없거나 틀리면 401(본문 없음)";

    String WORK_ID_RULE = "workId는 1~100자 영문·숫자·`:`·`_`·`-`이고 아니면 400 INVALID_WORK_ID다.";

    @Operation(summary = "쿠폰 할인 계산", description = """
            주문할 강의마다 쿠폰 할인·구매자 결제액·판매자 지급액과 쿠폰별 계산 근거를 돌려준다. 아무것도 바꾸지 않는다.
            - `selections`가 없거나 `null`이면 할인 합이 가장 큰 배치를 추천한다(`recommended=true`). 탐색을 끝까지 마쳤을 때만 `optimal=true`이고, 탐색 상한을 넘어 찾은 최선이면 `optimal=false`다.
            - `selections`가 `[]`이면 쿠폰을 쓰지 않는다(할인 0).
            - 값이 있으면 그 매핑만 검증·계산한다. 매핑에 없는 강의는 추천으로 채우지 않는다.
            추천이 아니면(`[]`·매핑) `recommended=false`, `optimal=false`다.
            강의마다 강의 조회로 가격·판매자를 다시 확인한다.
            플랫폼 재원이 꺼져 있으면(`hapbang.coupon.platform-funding-enabled=false`, 기본값) 추천 후보에서 플랫폼 쿠폰을 빼고, 매핑에 플랫폼 쿠폰이 있으면 409 COUPON_NOT_USABLE(detail `PLATFORM_FUNDING_DISABLED`, memberCouponId 포함)이다.

            오류: 항목이 많음 400 COUPON_TOO_MANY_ITEMS, 같은 강의 두 번 400 COUPON_DUPLICATE_LECTURE, 필수 값 없음·빈 items·음수 가격 400 INVALID_REQUEST,
            강의 없음 404 LECTURE_NOT_FOUND, 가격·판매자 불일치 409 COUPON_LECTURE_PRICE_MISMATCH(detail에 강의 ID), 적용 대상 아님 409 COUPON_NOT_APPLICABLE, 사용 불가(소유·상태·기한) 409 COUPON_NOT_USABLE,
            중첩 위반 409 COUPON_STACKING_VIOLATION, 강의 조회 불가 503 LECTURE_CATALOG_UNAVAILABLE.""")
    @Parameter(name = InternalApiHeaders.API_KEY, in = ParameterIn.HEADER, required = true,
            description = API_KEY_DESCRIPTION)
    RsData<CouponCalculateResponse> calculate(CouponCalculateRequest request);

    @Operation(summary = "쿠폰 예약", description = """
            주문 항목별 쿠폰을 한 트랜잭션에서 모두 예약하거나 하나도 예약하지 않는다. 소유·기한·상태·정책 버전·적용 대상·중첩·순서를 다시 검증하고
            금액을 주문 스냅샷 가격으로 다시 계산해 요청 근거와 대조한다. 같은 workId·같은 내용이면 처음 결과(성공 또는 같은 코드·같은 memberCouponId의 거절)를
            그대로 다시 준다. 응답 items는 저장한 사용 이력으로 만들어 쿠폰이 없는 주문 항목은 빠진다.
            해제한 쿠폰은 같은 주문 항목에 새 generation으로 다시 예약할 수 있다.
            """ + WORK_ID_RULE + """

            오류(409에는 실패한 쿠폰의 `memberCouponId`가 함께 온다): generation 불일치 409 COUPON_GENERATION_MISMATCH,
            정책 버전 변경 409 COUPON_POLICY_VERSION_CHANGED, 금액 근거 불일치 409 COUPON_AMOUNT_MISMATCH, 적용 대상 아님 409 COUPON_NOT_APPLICABLE,
            사용 불가 409 COUPON_NOT_USABLE, 중첩 위반 409 COUPON_STACKING_VIOLATION,
            같은 workId에 다른 내용 409 COUPON_WORK_CONFLICT, 항목이 많음 400 COUPON_TOO_MANY_ITEMS, 같은 강의 두 번 400 COUPON_DUPLICATE_LECTURE, 필수 값 없음 400 INVALID_REQUEST.""")
    @Parameter(name = InternalApiHeaders.API_KEY, in = ParameterIn.HEADER, required = true,
            description = API_KEY_DESCRIPTION)
    RsData<CouponReserveResponse> reserve(CouponReserveRequest request);

    @Operation(summary = "쿠폰 예약 해제", description = """
            나열한 예약(RESERVED) 사용 시도를 한 트랜잭션에서 모두 해제하거나 하나도 해제하지 않는다. 만료 전이면 AVAILABLE, 만료 뒤면 EXPIRED로 끝난다.
            같은 workId·같은 내용이면 처음 결과를 그대로 다시 준다. 작업 내용 비교의 회원은 사용 시도에 저장된 회원이다.
            `reason`은 500자, `compensationRef`는 200자까지이고 `compensationRef`는 기록만 한다.
            """ + WORK_ID_RULE + """

            오류: 사용됨(USED)·다른 주문·다른 generation·없는 사용 시도 409 COUPON_RELEASE_NOT_ALLOWED, 같은 workId에 다른 내용 409 COUPON_WORK_CONFLICT,
            필수 값 없음·빈 usages·사유·근거 길이 초과 400 INVALID_REQUEST.""")
    @Parameter(name = InternalApiHeaders.API_KEY, in = ParameterIn.HEADER, required = true,
            description = API_KEY_DESCRIPTION)
    RsData<CouponReleaseResponse> release(CouponReleaseRequest request);

    @Operation(summary = "쿠폰 예약 상태", description = """
            예약 작업 하나의 결과. 읽기만 한다. `status`: RESERVED(예약됨), RELEASED(모두 해제됨), REJECTED(거절됨, `rejectCode`에 거절 코드만),
            NOT_FOUND(기록 없음. 회원·주문이 다를 때도). `items`는 예약 응답과 같은 모양(쿠폰이 없는 주문 항목은 빠짐)에 사용 시도별 `status`(RESERVED·RELEASED)를 더했다.
            """ + WORK_ID_RULE + """

            그 밖의 오류: memberId·orderId 없음 400 INVALID_REQUEST.""")
    @Parameter(name = InternalApiHeaders.API_KEY, in = ParameterIn.HEADER, required = true,
            description = API_KEY_DESCRIPTION)
    RsData<CouponReservationStatusResponse> reservationStatus(
            @Parameter(description = "예약 작업 ID", required = true) String workId,
            @Parameter(description = "주문한 회원 ID", required = true) Long memberId,
            @Parameter(description = "주문 ID", required = true) Long orderId);

    @Operation(summary = "가격 인상 제한", description = """
            강의 가격을 올릴 수 없는지. 읽기만 한다. 그 강의 대상 플랫폼 쿠폰이 발급 중(OPEN)이거나, 그 쿠폰의 예약된(RESERVED) 발급 쿠폰 또는
            만료 전 사용 가능한(AVAILABLE) 발급 쿠폰이 있으면 `blocked=true`다. 판매자 쿠폰은 보지 않는다.

            오류: lectureId 없음·숫자 아님 400 INVALID_REQUEST.""")
    @Parameter(name = InternalApiHeaders.API_KEY, in = ParameterIn.HEADER, required = true,
            description = API_KEY_DESCRIPTION)
    RsData<CouponPriceRaiseBlockedResponse> priceRaiseBlocked(
            @Parameter(description = "강의 ID", required = true) Long lectureId);
}
