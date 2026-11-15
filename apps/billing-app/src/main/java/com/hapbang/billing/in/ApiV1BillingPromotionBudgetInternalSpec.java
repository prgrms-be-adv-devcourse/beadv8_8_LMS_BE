package com.hapbang.billing.in;

import com.hapbang.billing.global.response.RsData;
import com.hapbang.shared.billing.dto.PromotionBudgetReleaseCommand;
import com.hapbang.shared.billing.dto.PromotionBudgetReleaseResultDto;
import com.hapbang.shared.billing.dto.PromotionBudgetReservationDto;
import com.hapbang.shared.billing.dto.PromotionBudgetReserveCommand;
import com.hapbang.shared.billing.dto.PromotionBudgetReserveResultDto;
import com.hapbang.shared.billing.dto.PromotionBudgetWorkDto;
import com.hapbang.shared.internal.InternalApiHeaders;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;

// 쿠폰 예산 내부 API의 Swagger 명세이며, 매핑은 ApiV1BillingPromotionBudgetInternalController가 맡는다.
@Tag(name = "쿠폰 예산 (내부)", description = "모듈 간 내부 API. 쿠폰이 플랫폼 쿠폰의 예산을 프로모션 지갑에서 예약·해제하고 결과를 조회한다"
        + "(docs/api/internal-billing.md). 회원 로그인으로는 호출할 수 없고, 내부 포트로만 받는다.")
public interface ApiV1BillingPromotionBudgetInternalSpec {

    String COMMON_ERRORS = " 같은 작업 ID·다른 내용(또는 다른 종류의 작업에 쓴 작업 ID) 409 IDEMPOTENCY_KEY_CONFLICT. "
            + "금액 0 이하 400 INVALID_POINT_AMOUNT. 필수 값 누락·형식 오류 400 INVALID_REQUEST. 락 대기 초과 503 HP_LOCK_TIMEOUT"
            + "(처리하지 않음, 같은 요청을 다시 보낸다). 거절은 아무것도 저장하지 않는다.";

    @Operation(summary = "쿠폰 예산 예약",
            description = "프로모션 지갑의 예약되지 않은 가용 HP를 쿠폰 하나의 예산(예약 칸)으로 옮긴다(원장 BUDGET_RESERVE). "
                    + "쿠폰당 예약은 하나이고 멱등 키는 workId다. 같은 내용 재요청은 replayed=true. "
                    + "오류: 가용 HP 부족 409 PROMOTION_BALANCE_INSUFFICIENT, 같은 쿠폰에 다른 workId의 예약 409 BUDGET_RESERVATION_EXISTS."
                    + COMMON_ERRORS)
    @Parameter(name = InternalApiHeaders.API_KEY, in = ParameterIn.HEADER, required = true,
            description = ApiV1BillingInternalSpec.API_KEY_DESCRIPTION)
    RsData<PromotionBudgetReserveResultDto> reserve(PromotionBudgetReserveCommand command);

    @Operation(summary = "쿠폰 예산 해제",
            description = "예약의 남은 예산에서 amountHp를 빼 프로모션 지갑 가용 칸으로 되돌린다(원장 BUDGET_RELEASE). 주문 항목에 보류 중인 "
                    + "예산은 해제하지 않는다. target은 기록만 한다. 멱등 키는 workId다. 같은 내용 재요청은 replayed=true이고 "
                    + "remainingHp는 응답 시점 값이다. 오류: 예약 없음 404 BUDGET_RESERVATION_NOT_FOUND, "
                    + "남은 예산 초과 409 BUDGET_RELEASE_EXCEEDS_REMAINING." + COMMON_ERRORS)
    @Parameter(name = InternalApiHeaders.API_KEY, in = ParameterIn.HEADER, required = true,
            description = ApiV1BillingInternalSpec.API_KEY_DESCRIPTION)
    RsData<PromotionBudgetReleaseResultDto> release(PromotionBudgetReleaseCommand command);

    @Operation(summary = "쿠폰 예산 작업 조회",
            description = "응답을 잃었을 때 작업 ID로 결과를 확인한다. HP는 움직이지 않는다. 처리한 작업이면 status=DONE과 종류(RESERVE·RELEASE)·"
                    + "예약 ID·금액(예약은 총액, 해제는 해제 금액)을 준다. 기록이 없으면 200에 status=NOT_FOUND이고 같은 workId로 다시 보내면 된다"
                    + "(거절한 작업은 저장하지 않는다). 오류: 작업 ID 형식 오류 400 INVALID_REQUEST.")
    @Parameter(name = InternalApiHeaders.API_KEY, in = ParameterIn.HEADER, required = true,
            description = ApiV1BillingInternalSpec.API_KEY_DESCRIPTION)
    RsData<PromotionBudgetWorkDto> getWork(@Parameter(description = "작업 ID") String workId);

    @Operation(summary = "쿠폰 예산 예약 조회",
            description = "예약 총액과 남은·보류 중·지급·해제 예산이다(총액 = 네 값의 합). HP는 움직이지 않는다. "
                    + "오류: 예약 없음 404 BUDGET_RESERVATION_NOT_FOUND, 예약 ID 형식 오류 400 INVALID_REQUEST.")
    @Parameter(name = InternalApiHeaders.API_KEY, in = ParameterIn.HEADER, required = true,
            description = ApiV1BillingInternalSpec.API_KEY_DESCRIPTION)
    RsData<PromotionBudgetReservationDto> getReservation(
            @Parameter(description = "예산 예약 ID (PB-{숫자})", example = "PB-7") String reservationId);
}
