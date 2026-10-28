package com.hapbang.billing.in;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.hapbang.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

// 수익 HP 환전 신청 API의 Swagger 명세이며, 매핑은 ApiV1SettlementExchangeController가 맡는다 (#285).
@Tag(name = "환전", description = "수익 HP를 KRW로 환전 신청한다(신청 시 수익 HP 보류, 정산일 송금은 후속 기능)")
public interface ApiV1SettlementExchangeSpec {

    @Operation(summary = "환전 신청",
            description = "인플루언서·인증된 인플루언서. 가용 수익 HP만 환전한다(충전 HP 불가). 금액은 1,000 이상·1,000 단위"
                    + "(400 EXCHANGE_AMOUNT_INVALID). 신청과 함께 신청 금액만큼 수익 HP를 보류하고, 수수료(10%, 원 미만 버림)와 "
                    + "예상 수령액을 확정한다. 계좌는 신청마다 입력하는 테스트 계좌(은행 코드 숫자 3자리, 계좌번호 숫자 8~20자리, "
                    + "예금주 1~50자, 형식 오류 400 INVALID_REQUEST)이며 실제 계좌 확인·송금은 하지 않는다. 201 REQUESTED. "
                    + "Idempotency-Key 헤더 필수(영문·숫자·-·_ 1~64자, 400 EXCHANGE_IDEMPOTENCY_KEY_INVALID): 같은 키 재요청은 처음 "
                    + "신청의 현재 결과(금액·계좌가 다르면 409 IDEMPOTENCY_KEY_CONFLICT). 수익 HP 부족 409 INSUFFICIENT_BALANCE, "
                    + "지갑 없음 404 WALLET_NOT_FOUND, 암호화 설정 없음 503 EXCHANGE_UNAVAILABLE, 비로그인 401, 유저·관리자 403.")
    RsData<ExchangeResponse> request(Long userId,
            @Parameter(description = "환전 신청 멱등키(클라이언트가 신청마다 새로 만든다)", required = true) String idempotencyKey,
            @Valid ExchangeRequest request);

    @Operation(summary = "내 환전 신청 목록", description = "최신 신청부터. page·size(최대 100) 페이지네이션.")
    RsData<Page<ExchangeResponse>> getExchanges(Long userId, Pageable pageable);

    @Operation(summary = "환전 신청 조회", description = "내 환전 신청의 상태·수수료·마스킹한 계좌. 없거나 남의 신청은 404 EXCHANGE_NOT_FOUND.")
    RsData<ExchangeResponse> getExchange(Long userId, Long exchangeId);

    @Operation(summary = "환전 신청 취소",
            description = "관리자 승인 전(REQUESTED)인 내 신청을 취소하고 보류한 수익 HP를 모두 반환한다(200 CANCELED). 이미 취소한 신청은 "
                    + "반환 없이 같은 결과(200 CANCELED). 관리자가 승인·거절한 신청과 송금 단계 이후는 409 EXCHANGE_NOT_CANCELABLE. 없거나 남의 신청은 404 "
                    + "EXCHANGE_NOT_FOUND.")
    RsData<ExchangeResponse> cancel(Long userId, Long exchangeId);
}
