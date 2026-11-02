package com.hapbang.billing.in;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.hapbang.billing.domain.ExchangePayoutStatus;
import com.hapbang.billing.domain.ExchangeStatus;
import com.hapbang.billing.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

// 관리자 환전 조회·승인·거절(#294)·송금 운영자 처리(#304) API의 Swagger 명세이며, 매핑은 ApiV1SettlementExchangeAdminController가 맡는다.
@Tag(name = "환전 관리(관리자)", description = "환전 신청을 승인·거절하고, 송금 결과 불명·확정 실패 건을 확인·재송금·최종 실패 처리한다")
public interface ApiV1SettlementExchangeAdminSpec {

    @Operation(summary = "환전 신청 목록",
            description = "관리자. 상태별(기본 REQUESTED) 페이지(최대 100). 승인 대기(REQUESTED)는 오래된 신청부터, 그 밖의 상태는 최신부터. "
                    + "계좌번호는 마스킹한 값만 준다. applicant는 Billing 회원 복제본의 현재 정보이며 없으면 null(승인 불가).")
    RsData<Page<AdminExchangeResponse>> getExchanges(
            @Parameter(description = "상태(기본 REQUESTED)") ExchangeStatus status, Pageable pageable);

    @Operation(summary = "환전 신청 상세", description = "관리자. 없으면 404 EXCHANGE_NOT_FOUND.")
    RsData<AdminExchangeResponse> getExchange(Long exchangeId);

    @Operation(summary = "환전 신청 승인",
            description = "관리자. 승인 대기(REQUESTED) 신청을 APPROVED로 바꾼다. 신청자의 수익 HP 보류는 그대로 두고(송금·확정 차감 없음) "
                    + "잠정 정산 예정일도 바꾸지 않는다. 신청자가 Billing 회원 복제본 기준 정상(ACTIVE) 상태의 인플루언서·인증된 "
                    + "인플루언서여야 한다. **Billing 회원 복제본이 없는 신청자(복제본 도입 전에 가입하고 이후 회원 정보가 바뀌지 않은 회원, "
                    + "기존 회원 Backfill 전)는 승인할 수 없다: 409 EXCHANGE_APPLICANT_NOT_SYNCED**(상태·보류 변경 없음). 자격이 없으면 "
                    + "409 EXCHANGE_APPLICANT_NOT_ELIGIBLE. 같은 관리자의 재승인은 처음 결과(200), 다른 관리자의 승인·거절된 신청·취소된 "
                    + "신청·송금 단계 신청은 409 EXCHANGE_ALREADY_REVIEWED. 없으면 404, 비로그인 401, 관리자가 아니면 403.")
    RsData<AdminExchangeResponse> approve(Long adminId, Long exchangeId);

    @Operation(summary = "환전 신청 거절",
            description = "관리자. 승인 대기(REQUESTED) 신청을 REJECTED로 바꾸고 보류한 수익 HP를 전부 신청자의 수익 HP로 반환한다(같은 "
                    + "트랜잭션). 신청자의 현재 회원 상태와 관계없이 거절할 수 있다. 사유는 앞뒤 공백을 뺀 1~500자(400 "
                    + "EXCHANGE_REJECTION_REASON_INVALID, 비었으면 400 INVALID_REQUEST). 같은 관리자·같은 사유의 재거절은 처음 결과(200, "
                    + "다시 반환하지 않음), 다른 관리자·다른 사유·승인된 신청·취소된 신청은 409 EXCHANGE_ALREADY_REVIEWED.")
    RsData<AdminExchangeResponse> reject(Long adminId, Long exchangeId, @Valid ExchangeRejectRequest request);

    @Operation(summary = "환전 송금 시도 목록(운영자 확인 대상)",
            description = "관리자. 송금 시도 상태별(기본 MANUAL_REVIEW_REQUIRED) 페이지(최대 100), 오래된 순. 운영자 확인 대상은 은행 409·4xx "
                    + "거절, 성공 응답 불일치, 최초 송금 요청 후 24시간 동안 결과가 확정되지 않은 시도다(보류 유지, 자동 처리 중단). "
                    + "송금 시도가 확정 실패한 환전(관리자 판단 대기)은 환전 목록의 status=FAILED로 조회한다. 계좌번호는 담지 않는다.")
    RsData<Page<AdminExchangePayoutResponse>> getPayoutAttempts(
            @Parameter(description = "송금 시도 상태(기본 MANUAL_REVIEW_REQUIRED)") ExchangePayoutStatus status,
            Pageable pageable);

    @Operation(summary = "환전 송금 시도 이력", description = "관리자. 환전의 송금 시도 전부(시도 번호 순). 없으면 404 EXCHANGE_NOT_FOUND.")
    RsData<List<AdminExchangePayoutResponse>> getPayoutAttemptsOf(Long exchangeId);

    @Operation(summary = "환전 송금 결과 재확인",
            description = "관리자. 결과가 확정되지 않은 마지막 송금 시도(PROCESSING·UNKNOWN·MANUAL_REVIEW_REQUIRED)를 **기존 지급 요청 ID**로 "
                    + "은행에 조회만 한다(새로 보내지 않는다). 성공이면 보류 확정 차감 후 COMPLETED, 은행 확정 실패면 FAILED(보류 유지), "
                    + "운영자 확인 시도인데 은행에 그 ID의 기록이 없으면(마지막 송금·자동 확인 후 1분 경과) 송금되지 않은 것으로 보고 "
                    + "FAILED(BANK_RECORD_NOT_FOUND). 결과를 여전히 모르면 상태 그대로. 재확인한 관리자·시각을 남긴다. 그 밖의 상태는 409 "
                    + "EXCHANGE_PAYOUT_NOT_CHECKABLE, 송금을 쓸 수 없는 환경이면 503 EXCHANGE_PAYOUT_UNAVAILABLE.")
    RsData<AdminExchangePayoutResponse> recheckPayout(Long adminId, Long exchangeId);

    @Operation(summary = "환전 수동 재송금",
            description = "관리자. 마지막 송금 시도가 은행 확정 실패(환전 FAILED)인 환전만 **새 지급 요청 ID**의 새 시도로 다시 보낸다(보낸 "
                    + "관리자 기록). 결과 대기·결과 불명·운영자 확인(성공 가능성이 남음)·성공·최종 실패 등 그 밖의 상태는 409 "
                    + "EXCHANGE_PAYOUT_NOT_RETRYABLE. 확정 실패는 자동으로 다시 보내지 않는다.")
    RsData<AdminExchangePayoutResponse> retryPayout(Long adminId, Long exchangeId);

    @Operation(summary = "환전 최종 실패 확정",
            description = "관리자. 마지막 송금 시도가 은행 확정 실패(환전 FAILED)인 환전을 FINAL_FAILED로 종료하고, 보류한 수익 HP 전부를 "
                    + "신청자의 수익 HP(가용)로 반환한다(한 트랜잭션). 결과 불명·운영자 확인 시도는 먼저 결과 재확인으로 실패를 확정해야 "
                    + "한다(임의로 실패 처리·반환할 수 없다). 성공·결과 대기·그 밖의 상태는 409 EXCHANGE_PAYOUT_NOT_FINALIZABLE. "
                    + "사유는 앞뒤 공백을 뺀 1~500자(400 EXCHANGE_FINAL_FAILURE_REASON_INVALID). 같은 관리자·같은 사유 재요청은 처음 결과"
                    + "(다시 반환하지 않음). 이후 재송금·확정 차감은 하지 않는다.")
    RsData<AdminExchangeResponse> failPayoutFinally(Long adminId, Long exchangeId,
            @Valid ExchangeFinalFailureRequest request);
}
