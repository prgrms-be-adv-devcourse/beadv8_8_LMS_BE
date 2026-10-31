package com.hapbang.billing.in;

import com.hapbang.billing.global.response.RsData;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.HoldResultDto;
import com.hapbang.shared.billing.dto.HoldVoidCommand;
import com.hapbang.shared.billing.dto.HoldVoidResultDto;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.dto.PayoutResultDto;
import com.hapbang.shared.billing.dto.PenaltyCommand;
import com.hapbang.shared.billing.dto.PenaltyResultDto;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.shared.billing.dto.ReleaseResultDto;
import com.hapbang.shared.internal.InternalApiHeaders;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;

// 지갑 내부 API(보류·반환·지급·위약금 정산)의 Swagger 명세이며, 매핑은 ApiV1BillingInternalController가 맡는다 (#273).
@Tag(name = "HP 보류·반환·지급 (내부)", description = "모듈 간 내부 API. 강의·출연이 HP를 보류·반환·지급하고 출연 취소 위약금을 정산할 때 "
        + "호출한다(docs/api/internal-billing.md). 회원 세션으로는 호출할 수 없고, 서버 밖에서는 진입점이 막는다.")
public interface ApiV1BillingInternalSpec {

    String API_KEY_DESCRIPTION = "내부 API 키 (hapbang.internal-api.key). 없거나 틀리면 401(본문 없음)";

    String COMMON_ERRORS = " 같은 멱등 키·다른 내용 409 IDEMPOTENCY_KEY_CONFLICT. 필수 값 누락·형식 오류 400 INVALID_REQUEST. "
            + "락 대기 초과 503 HP_LOCK_TIMEOUT(처리하지 않음, 같은 요청을 다시 보낸다).";

    @Operation(summary = "HP 보류",
            description = "결제 회원(userId)의 HP를 참조 대상 하나에 보류한다. 수익 HP를 먼저 쓰고 부족분은 오래된 충전 건부터(FIFO) 쓴다. "
                    + "멱등 키는 참조 대상(referenceType + referenceId)이며 참조 대상당 보류 1건이다. 같은 내용 재요청은 replayed=true. "
                    + "오류: 지갑 없음 404 WALLET_NOT_FOUND, 잔액 부족 409 INSUFFICIENT_BALANCE, 금액 0 이하 400 INVALID_POINT_AMOUNT."
                    + COMMON_ERRORS)
    @Parameter(name = InternalApiHeaders.API_KEY, in = ParameterIn.HEADER, required = true,
            description = API_KEY_DESCRIPTION)
    RsData<HoldResultDto> hold(HoldCommand command);

    @Operation(summary = "HP 반환",
            description = "참조 대상 보류의 남은 HP 일부(amountHp) 또는 전부(amountHp 생략)를 결제 회원에게 반환한다. "
                    + "멱등 키는 보류 하나 안의 releaseRequestId다. 같은 내용 재요청은 replayed=true. "
                    + "오류: 보류 없음 404 HOLD_NOT_FOUND, 다른 회원의 보류 409 HOLD_OWNER_MISMATCH, 남은 보류 없음 409 HOLD_ALREADY_CLOSED, "
                    + "남은 보류 초과 409 RELEASE_AMOUNT_EXCEEDS_HELD." + COMMON_ERRORS)
    @Parameter(name = InternalApiHeaders.API_KEY, in = ParameterIn.HEADER, required = true,
            description = API_KEY_DESCRIPTION)
    RsData<ReleaseResultDto> release(ReleaseCommand command);

    @Operation(summary = "HP 지급",
            description = "참조 대상 보류의 남은 HP 일부(amountHp) 또는 전부(amountHp 생략)를 받는 회원의 수익 HP로 지급한다. "
                    + "멱등 키는 보류 하나 안의 payoutRequestId다. 같은 내용 재요청은 replayed=true. "
                    + "오류: 결제·받는 회원 지갑 없음 404 WALLET_NOT_FOUND, 보류 없음 404 HOLD_NOT_FOUND, 남은 보류 없음 409 HOLD_ALREADY_CLOSED, "
                    + "같은 지급 원천에 이미 지급 409 PAYOUT_ALREADY_SETTLED, 남은 보류 초과 409 PAYOUT_AMOUNT_EXCEEDS_HELD, "
                    + "자기 지급 400 PAYOUT_TO_PAYER, 지급 원천 불일치 400 PAYOUT_SOURCE_MISMATCH." + COMMON_ERRORS)
    @Parameter(name = InternalApiHeaders.API_KEY, in = ParameterIn.HEADER, required = true,
            description = API_KEY_DESCRIPTION)
    RsData<PayoutResultDto> payout(PayoutCommand command);

    @Operation(summary = "출연 취소 위약금 정산",
            description = "호스트 일방 취소 때 출연이 계산한 호스트 반환액(releaseAmountHp ≥ 0)과 게스트 지급액(payoutAmountHp > 0)을 "
                    + "한 트랜잭션으로 처리한다. 위약금 비율은 Billing이 계산하지 않는다. 멱등 키는 보류 하나 안의 cancellationId다. "
                    + "오류: 합계가 남은 보류 초과 409 PENALTY_BASE_EXCEEDS_HELD, 게스트 지급액 0 400 PENALTY_AMOUNT_ZERO, "
                    + "보류 없음 404 HOLD_NOT_FOUND, 남은 보류 없음 409 HOLD_ALREADY_CLOSED." + COMMON_ERRORS)
    @Parameter(name = InternalApiHeaders.API_KEY, in = ParameterIn.HEADER, required = true,
            description = API_KEY_DESCRIPTION)
    RsData<PenaltyResultDto> settlePenalty(PenaltyCommand command);

    @Operation(summary = "HP 보류 무효화",
            description = "참조 대상의 보류를 더는 쓰지 않을 때 부른다(#479). 보류가 있고 남은 HP가 있으면 결제 회원에게 전부 반환한다(RELEASED). "
                    + "보류가 이미 끝났으면 바꾸지 않는다(ALREADY_CLOSED). 보류가 없으면 무효 표시만 남긴다(VOIDED). 어느 경우든 이후 이 "
                    + "참조 대상의 보류 요청은 409 HOLD_VOIDED로 거절한다. 멱등 키는 참조 대상(referenceType + referenceId)이며 참조 대상당 "
                    + "한 번이다. 같은 요청 재요청은 처음 결과를 replayed=true로 준다. "
                    + "오류: 지갑 없음 404 WALLET_NOT_FOUND, 다른 회원의 보류·무효화 409 HOLD_OWNER_MISMATCH." + COMMON_ERRORS)
    @Parameter(name = InternalApiHeaders.API_KEY, in = ParameterIn.HEADER, required = true,
            description = API_KEY_DESCRIPTION)
    RsData<HoldVoidResultDto> voidHold(HoldVoidCommand command);
}
