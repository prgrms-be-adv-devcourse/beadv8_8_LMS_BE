package com.hapbang.billing.in;

import java.time.Instant;
import java.util.List;

import com.hapbang.billing.global.response.RsData;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.HoldQueryCommand;
import com.hapbang.shared.billing.dto.HoldResultDto;
import com.hapbang.shared.billing.dto.HoldStateDto;
import com.hapbang.shared.billing.dto.HoldStatePageDto;
import com.hapbang.shared.billing.dto.HoldVoidCommand;
import com.hapbang.shared.billing.dto.HoldVoidResultDto;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.dto.PayoutResultDto;
import com.hapbang.shared.billing.dto.PenaltyCommand;
import com.hapbang.shared.billing.dto.PenaltyResultDto;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.shared.billing.dto.ReleaseResultDto;
import com.hapbang.shared.billing.dto.WithdrawalCloseResultDto;
import com.hapbang.shared.billing.dto.WithdrawalReopenResultDto;
import com.hapbang.shared.internal.InternalApiHeaders;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;

// 지갑 내부 API(보류·반환·지급·위약금 정산)의 Swagger 명세이며, 매핑은 ApiV1BillingInternalController가 맡는다 (#273).
@Tag(name = "HP 보류·반환·지급 (내부)", description = "모듈 간 내부 API. 강의·출연이 HP를 보류·반환·지급하고 출연 취소 위약금을 정산할 때 "
        + "호출한다(docs/api/internal-billing.md). 회원 로그인으로는 호출할 수 없고, 내부 포트로만 받는다(외부 포트로 오면 404).")
public interface ApiV1BillingInternalSpec {

    String API_KEY_DESCRIPTION = "내부 API 키 (hapbang.internal-api.key). 없거나 틀리면 401(본문 없음)";

    String COMMON_ERRORS = " 같은 멱등 키·다른 내용 409 IDEMPOTENCY_KEY_CONFLICT. 필수 값 누락·형식 오류 400 INVALID_REQUEST. "
            + "락 대기 초과 503 HP_LOCK_TIMEOUT(처리하지 않음, 같은 요청을 다시 보낸다).";

    @Operation(summary = "HP 보류",
            description = "결제 회원(userId)의 HP를 참조 대상 하나에 보류한다. 수익 HP를 먼저 쓰고 부족분은 오래된 충전 건부터(FIFO) 쓴다. "
                    + "멱등 키는 참조 대상(referenceType + referenceId)이며 참조 대상당 보류 1건이다. 같은 내용 재요청은 replayed=true. "
                    + "오류: 지갑 없음 404 WALLET_NOT_FOUND, 잔액 부족 409 INSUFFICIENT_BALANCE, 구매자 금액 음수 또는 프로모션 없는 0원 400 INVALID_POINT_AMOUNT. "
                    + "강의 주문 항목은 promotions로 쿠폰 예산도 함께 보류한다. 구매자 0원·플랫폼 양수는 허용한다. "
                    + "예약 없음 404 BUDGET_RESERVATION_NOT_FOUND, 예약 잔액 부족 409 PROMOTION_BUDGET_INSUFFICIENT."
                    + COMMON_ERRORS)
    @Parameter(name = InternalApiHeaders.API_KEY, in = ParameterIn.HEADER, required = true,
            description = API_KEY_DESCRIPTION)
    RsData<HoldResultDto> hold(HoldCommand command);

    @Operation(summary = "HP 반환",
            description = "참조 대상 보류의 남은 HP 일부(amountHp) 또는 전부(amountHp 생략)를 결제 회원에게 반환한다. "
                    + "멱등 키는 보류 하나 안의 releaseRequestId다. 같은 내용 재요청은 replayed=true. "
                    + "오류: 보류 없음 404 HOLD_NOT_FOUND, 다른 회원의 보류 409 HOLD_OWNER_MISMATCH, 남은 보류 없음 409 HOLD_ALREADY_CLOSED, "
                    + "남은 보류 초과 409 RELEASE_AMOUNT_EXCEEDS_HELD. "
                    + "플랫폼 몫은 같은 쿠폰 예약으로 복구하며 프로모션 보류의 부분 반환은 409 PROMOTION_PARTIAL_NOT_SUPPORTED." + COMMON_ERRORS)
    @Parameter(name = InternalApiHeaders.API_KEY, in = ParameterIn.HEADER, required = true,
            description = API_KEY_DESCRIPTION)
    RsData<ReleaseResultDto> release(ReleaseCommand command);

    @Operation(summary = "HP 지급",
            description = "참조 대상 보류의 남은 HP 일부(amountHp) 또는 전부(amountHp 생략)를 받는 회원의 수익 HP로 지급한다. "
                    + "멱등 키는 보류 하나 안의 payoutRequestId다. 같은 내용 재요청은 replayed=true. "
                    + "오류: 결제·받는 회원 지갑 없음 404 WALLET_NOT_FOUND, 보류 없음 404 HOLD_NOT_FOUND, 남은 보류 없음 409 HOLD_ALREADY_CLOSED, "
                    + "같은 지급 원천에 이미 지급 409 PAYOUT_ALREADY_SETTLED, 남은 보류 초과 409 PAYOUT_AMOUNT_EXCEEDS_HELD, "
                    + "자기 지급 400 PAYOUT_TO_PAYER, 지급 원천 불일치 400 PAYOUT_SOURCE_MISMATCH. "
                    + "프로모션 보류는 구매자·플랫폼 전액을 합산 지급하며 부분 지급은 409 PROMOTION_PARTIAL_NOT_SUPPORTED." + COMMON_ERRORS)
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

    @Operation(summary = "회원 탈퇴용 지갑 확인하고 닫기",
            description = "회원 앱이 탈퇴를 확인할 때 마지막으로 부른다(#623, docs/api/internal-withdrawals.md 4.1). 한 트랜잭션에서 "
                    + "지갑을 잠그고 막힌 이유(충전·수익 HP 잔액, 처리 중인 환불·환전, 결과 확인 중인 충전)를 확인한 뒤, 이유가 없으면 "
                    + "지갑을 닫는다(CLOSED, reasons 빈 목록). 이유가 있으면 닫지 않고 BLOCKED와 이유를 돌려준다. 출연·강의 보류는 그 "
                    + "모듈이 이유를 적으므로 여기서 적지 않는다. 지갑이 아직 없는 회원은 기록만 남기고 CLOSED다. 닫힌 지갑은 충전·보류·"
                    + "환불·환전 신청을 409 WALLET_CLOSED로 거절하고, 보류 반환·지급 받기·복구 배치는 받는다. 탈퇴 작업 ID가 멱등 키라 "
                    + "같은 요청을 다시 보내면 처음 결과를 replayed=true로 돌려준다. "
                    + "오류: 같은 탈퇴 작업 ID·다른 회원 409 IDEMPOTENCY_KEY_CONFLICT, 다시 열기를 이미 받은 탈퇴 작업 409 "
                    + "WITHDRAWAL_ALREADY_REOPENED(지갑을 닫지 않음), userId 없음 400 INVALID_REQUEST, 락 대기 초과 503 "
                    + "HP_LOCK_TIMEOUT(처리하지 않음).")
    @Parameter(name = InternalApiHeaders.API_KEY, in = ParameterIn.HEADER, required = true,
            description = API_KEY_DESCRIPTION)
    RsData<WithdrawalCloseResultDto> closeForWithdrawal(
            @Parameter(description = "회원 앱의 탈퇴 작업 ID (멱등 키)") long withdrawalId,
            WalletWithdrawalRequest request);

    @Operation(summary = "회원 탈퇴용 지갑 다시 열기",
            description = "보상(다른 모듈에서 막힘, 회원 저장 실패)과 복구(30일 안 재로그인)에서 부른다(#623, "
                    + "docs/api/internal-withdrawals.md 4.2). 이 탈퇴 작업으로 닫힌 지갑이면 다시 연다(REOPENED). 닫힌 적이 "
                    + "없으면(닫기를 받지 못함, BLOCKED, 다른 탈퇴 작업으로 닫힘) 지갑을 바꾸지 않는다(NOT_CLOSED). 두 경우 모두 기록을 "
                    + "남겨 이 탈퇴 작업의 늦은 닫기를 거절한다. 같은 요청을 다시 보내면 처음 결과를 replayed=true로 돌려준다. "
                    + "오류: 같은 탈퇴 작업 ID·다른 회원 409 IDEMPOTENCY_KEY_CONFLICT, userId 없음 400 INVALID_REQUEST, "
                    + "락 대기 초과 503 HP_LOCK_TIMEOUT(처리하지 않음).")
    @Parameter(name = InternalApiHeaders.API_KEY, in = ParameterIn.HEADER, required = true,
            description = API_KEY_DESCRIPTION)
    RsData<WithdrawalReopenResultDto> reopenForWithdrawal(
            @Parameter(description = "회원 앱의 탈퇴 작업 ID (멱등 키)") long withdrawalId,
            WalletWithdrawalRequest request);

    @Operation(summary = "참조 대상별 보류·지급 조회 (서비스 간 HP 대사)",
            description = "강의·출연이 자기 대상(주문 항목·요청서·모집글)을 지갑 기록과 비교할 때 부른다(#577). HP는 움직이지 않는다. "
                    + "요청한 참조 ID마다 하나씩, 요청 순서대로(같은 ID는 한 번) 준다. 지갑에 기록이 없으면 hold·voided가 null이고 "
                    + "settlements가 비어 있다. hold의 금액은 HP 종류별(chargeHp·revenueHp)과 합계(totalHp)이고, 종류마다 "
                    + "original = remaining + released + paid + captured 다. settlements는 그 보류에서 나간 지급 건 전부다. "
                    + "오류: 참조 ID 0개·500개 초과·보류할 수 없는 참조 유형 400 INVALID_REQUEST.")
    @Parameter(name = InternalApiHeaders.API_KEY, in = ParameterIn.HEADER, required = true,
            description = API_KEY_DESCRIPTION)
    RsData<List<HoldStateDto>> queryHolds(HoldQueryCommand command);

    @Operation(summary = "기간 안에 바뀐 보류 목록 (서비스 간 HP 대사)",
            description = "강의·출연이 지갑에만 있는 보류를 찾을 때 부른다(#577). 참조 유형별로 (updatedAfter, afterId) 뒤에 바뀐 보류를 "
                    + "마지막 변경 시각·보류 순으로 size개 준다. 항목 모양은 참조 대상별 조회와 같다. nextUpdatedAfter·nextAfterId는 "
                    + "항상 있고 이 페이지의 마지막 보류 위치다(빈 페이지면 요청한 커서 그대로). hasMore가 true면 다음 요청의 "
                    + "updatedAfter·afterId로 그대로 넘기고, false면 멈추고 커서를 저장해 다음 대사를 거기서 시작한다. "
                    + "최근 10초 안에 바뀐 보류는 주지 않는다. "
                    + "오류: size 1~500 밖·afterId 음수·보류할 수 없는 참조 유형·시각 형식 오류 400 INVALID_REQUEST.")
    @Parameter(name = InternalApiHeaders.API_KEY, in = ParameterIn.HEADER, required = true,
            description = API_KEY_DESCRIPTION)
    RsData<HoldStatePageDto> listHolds(
            @Parameter(description = "참조 유형 (LECTURE_ORDER_ITEM·APPEARANCE_REQUEST·APPEARANCE_RECRUIT)")
            BillingReferenceType referenceType,
            @Parameter(description = "이 시각 이후에 바뀐 보류부터 (ISO 8601, 예: 2026-11-01T00:00:00Z)") Instant updatedAfter,
            @Parameter(description = "같은 시각 안에서 이 커서 뒤부터 (처음은 0)") @PositiveOrZero long afterId,
            @Parameter(description = "한 페이지 건수 (1~500, 기본 100)") @Min(1) @Max(HoldStatePageDto.MAX_SIZE) int size);
}
