package com.hapbang.billing.domain;

import org.springframework.http.HttpStatus;

import com.hapbang.billing.global.exception.BaseErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// Billing API 오류 코드와 HTTP 상태·메시지를 정의한다.
@Getter
@RequiredArgsConstructor
public enum BillingErrorCode implements BaseErrorCode {
    WALLET_NOT_FOUND(HttpStatus.NOT_FOUND, "지갑을 찾을 수 없습니다."),
    ORIGINAL_LEDGER_NOT_FOUND(HttpStatus.NOT_FOUND, "역분개할 원장 항목을 찾을 수 없습니다."),
    INVALID_REVERSAL(HttpStatus.BAD_REQUEST, "역분개 요청이 원본 원장 항목과 맞지 않습니다."),
    INVALID_POINT_AMOUNT(HttpStatus.BAD_REQUEST, "HP 금액이 올바르지 않습니다."),
    INSUFFICIENT_BALANCE(HttpStatus.CONFLICT, "HP 잔액이 부족합니다."),
    BALANCE_OVERFLOW(HttpStatus.CONFLICT, "HP 잔액이 허용 범위를 넘습니다."),
    IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT, "같은 작업 키로 다른 내용의 원장 기록이 요청되었습니다."),
    HOLD_NOT_FOUND(HttpStatus.NOT_FOUND, "HP 보류를 찾을 수 없습니다."),
    HOLD_OWNER_MISMATCH(HttpStatus.CONFLICT, "요청한 회원의 HP 보류가 아닙니다."),
    HOLD_ALREADY_CLOSED(HttpStatus.CONFLICT, "남은 보류 HP가 없어 더 처리할 수 없습니다."),
    // 보류 무효화된 참조 대상에는 보류를 만들지 않는다 (#479)
    HOLD_VOIDED(HttpStatus.CONFLICT, "보류가 무효화된 대상이라 HP를 보류할 수 없습니다."),
    RELEASE_AMOUNT_EXCEEDS_HELD(HttpStatus.CONFLICT, "반환 금액이 남은 보류 HP보다 많습니다."),
    PAYOUT_AMOUNT_EXCEEDS_HELD(HttpStatus.CONFLICT, "지급 금액이 남은 보류 HP보다 많습니다."),
    PENALTY_BASE_EXCEEDS_HELD(HttpStatus.CONFLICT, "위약금 정산 기준액이 남은 보류 HP보다 많습니다."),
    PENALTY_AMOUNT_ZERO(HttpStatus.BAD_REQUEST, "위약금이 0 HP가 되는 정산 기준액은 정산할 수 없습니다."),
    PAYOUT_TO_PAYER(HttpStatus.BAD_REQUEST, "보류한 회원 자신에게는 지급할 수 없습니다."),
    PAYOUT_SOURCE_MISMATCH(HttpStatus.BAD_REQUEST, "지급 원천이 보류의 거래 대상과 맞지 않습니다."),
    PAYOUT_ALREADY_SETTLED(HttpStatus.CONFLICT, "같은 출연 계약·주문 항목(또는 같은 출연 요청)에 이미 지급했습니다."),
    // 종류별 보류·확정 차감 (#241)
    CAPTURE_AMOUNT_EXCEEDS_HELD(HttpStatus.CONFLICT, "확정 차감 금액이 남은 보류 HP보다 많습니다."),
    HOLD_TYPE_NOT_SUPPORTED(HttpStatus.BAD_REQUEST, "이 보류 종류에서는 할 수 없는 처리입니다."),
    HOLD_CHARGE_NOT_FOUND(HttpStatus.BAD_REQUEST, "보류에 배분되지 않은 충전 건입니다."),
    // HP 충전 (#224)
    CHARGE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "지금은 HP 충전을 사용할 수 없습니다."),
    CHARGE_AMOUNT_INVALID(HttpStatus.BAD_REQUEST, "충전 금액은 1,000 HP 이상, 1,000 HP 단위여야 합니다."),
    CHARGE_NOT_FOUND(HttpStatus.NOT_FOUND, "충전 주문을 찾을 수 없습니다."),
    CHARGE_AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "결제 금액이 충전 주문 금액과 다릅니다."),
    CHARGE_PAYMENT_KEY_CONFLICT(HttpStatus.CONFLICT, "다른 결제로 처리 중이거나 처리된 충전 주문, 또는 이미 사용된 결제입니다."),
    CHARGE_ALREADY_FAILED(HttpStatus.CONFLICT, "결제가 승인되지 않아 끝난 충전 주문입니다. 새 충전 주문으로 다시 결제해 주세요."),
    CHARGE_PAYMENT_REJECTED(HttpStatus.BAD_REQUEST, "결제가 승인되지 않았습니다."),
    // 충전 HP 환불 (#260)
    REFUND_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "지금은 HP 환불을 사용할 수 없습니다."),
    REFUND_AMOUNT_TOO_SMALL(HttpStatus.BAD_REQUEST, "환불 금액은 1 HP 이상이어야 합니다."),
    REFUND_IDEMPOTENCY_KEY_INVALID(HttpStatus.BAD_REQUEST, "Idempotency-Key 헤더는 영문·숫자·-·_ 1~64자여야 합니다."),
    REFUND_NOT_FOUND(HttpStatus.NOT_FOUND, "환불 요청을 찾을 수 없습니다."),
    REFUND_NOT_CANCELABLE(HttpStatus.CONFLICT, "환불이 이미 진행되었거나 끝나 취소할 수 없습니다."),
    // 수익 HP 환전 신청 (#285)
    EXCHANGE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "지금은 환전 신청을 사용할 수 없습니다."),
    EXCHANGE_AMOUNT_TOO_SMALL(HttpStatus.BAD_REQUEST, "환전 금액은 1 HP 이상이어야 합니다."),
    EXCHANGE_IDEMPOTENCY_KEY_INVALID(HttpStatus.BAD_REQUEST, "Idempotency-Key 헤더는 영문·숫자·-·_ 1~64자여야 합니다."),
    EXCHANGE_NOT_FOUND(HttpStatus.NOT_FOUND, "환전 신청을 찾을 수 없습니다."),
    EXCHANGE_NOT_CANCELABLE(HttpStatus.CONFLICT, "관리자 승인 전인 환전 신청만 취소할 수 있습니다."),
    // 관리자 환전 승인·거절 (#294)
    EXCHANGE_ALREADY_REVIEWED(HttpStatus.CONFLICT, "이미 처리되었거나 승인·거절할 수 없는 상태의 환전 신청입니다."),
    EXCHANGE_APPLICANT_NOT_SYNCED(HttpStatus.CONFLICT, "신청자의 회원 정보가 아직 Billing에 반영되지 않아 승인할 수 없습니다."),
    EXCHANGE_APPLICANT_NOT_ELIGIBLE(HttpStatus.CONFLICT, "신청자가 정상 상태의 인플루언서가 아니어서 승인할 수 없습니다."),
    EXCHANGE_REJECTION_REASON_INVALID(HttpStatus.BAD_REQUEST, "거절 사유는 앞뒤 공백을 빼고 1~500자여야 합니다."),
    // 환전 가상 송금 실행 (#304)
    EXCHANGE_NOT_PAYABLE(HttpStatus.CONFLICT, "관리자가 승인한 환전 신청만 송금할 수 있습니다."),
    EXCHANGE_PAYOUT_NOT_DUE(HttpStatus.CONFLICT, "정산 예정일 전에는 환전 송금을 할 수 없습니다."),
    EXCHANGE_PAYOUT_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "지금은 환전 송금을 처리할 수 없습니다."),
    // 환전 송금 운영자 처리 (#304)
    EXCHANGE_PAYOUT_NOT_RETRYABLE(HttpStatus.CONFLICT, "송금이 확정 실패한 환전만 다시 송금할 수 있습니다."),
    EXCHANGE_PAYOUT_NOT_FINALIZABLE(HttpStatus.CONFLICT, "송금 실패가 확정된 환전만 최종 실패로 종료할 수 있습니다."),
    EXCHANGE_PAYOUT_NOT_CHECKABLE(HttpStatus.CONFLICT, "결과를 다시 확인할 송금 시도가 없습니다."),
    EXCHANGE_FINAL_FAILURE_REASON_INVALID(HttpStatus.BAD_REQUEST, "최종 실패 사유는 앞뒤 공백을 빼고 1~500자여야 합니다."),
    // 지급 예정 수익 통합 조회 (#295). 강의·출연 중 한쪽이라도 조회하지 못하면 부분 결과를 주지 않고 전체를 실패시킨다
    EARNING_SOURCE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "지금은 수익 내역을 조회할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    // 강의·출연 내부 API가 요청을 거절했다(4xx: 내부 API 키·요청 계약 불일치). 다시 시도해도 같아 운영자 확인이 필요하다
    EARNING_SOURCE_REJECTED(HttpStatus.SERVICE_UNAVAILABLE, "지금은 수익 내역을 조회할 수 없습니다."),
    EARNING_PAGE_TOO_DEEP(HttpStatus.BAD_REQUEST, "수익 목록은 앞쪽 1,000건까지만 조회할 수 있습니다((page + 1) × size ≤ 1,000)."),
    // 기존 회원 BillingUser Backfill (#324). 회원 내부 API를 조회하지 못하거나 응답이 계약과 다르면 실행 전체를 멈춘다(이미 반영한 회원은 유지)
    MEMBER_SOURCE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "지금은 회원 정보를 조회할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    // 회원 내부 API가 요청을 거절했다(4xx: 내부 API 키·요청 계약 불일치). 다시 시도해도 같아 운영자 확인이 필요하다
    MEMBER_SOURCE_REJECTED(HttpStatus.SERVICE_UNAVAILABLE, "지금은 회원 정보를 조회할 수 없습니다."),
    BACKFILL_ALREADY_RUNNING(HttpStatus.CONFLICT, "회원 정보 Backfill이 이미 실행 중입니다."),
    // 실행 락(advisory lock)을 시도하지 못했다(DB 연결 불가 등). 아무것도 실행하지 않았으므로 다시 시도할 수 있다 (#598, BD-138)
    BACKFILL_LOCK_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "지금은 회원 정보 Backfill을 시작할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    // 지갑 회원 확인 (#537, BD-131). 로그인한 역할(토큰)과 함께 복제한 회원 상태·역할을 확인한다
    // 가입 직후 회원 이벤트가 아직 반영되지 않았을 수 있어 다시 시도할 수 있다
    WALLET_MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "회원 정보가 아직 지갑에 반영되지 않았습니다. 잠시 후 다시 시도해 주세요."),
    WALLET_MEMBER_NOT_ALLOWED(HttpStatus.FORBIDDEN, "회원 상태나 역할 때문에 이 신청을 할 수 없습니다."),
    // 회원 탈퇴 (#623, docs/api/internal-withdrawals.md 4장). 탈퇴로 닫힌 지갑은 새로 HP가 드나드는 신청을 받지 않는다
    WALLET_CLOSED(HttpStatus.CONFLICT, "탈퇴한 회원의 지갑이에요."),
    // 다시 열기를 이미 받은 탈퇴 작업의 늦은 닫기. 지갑을 닫지 않는다
    WITHDRAWAL_ALREADY_REOPENED(HttpStatus.CONFLICT, "이미 다시 열기를 처리한 탈퇴 작업이라 지갑을 닫을 수 없습니다."),
    // 프로모션 지갑의 예약되지 않은 가용 잔액이 요청 금액보다 적다. 예약·보류 칸은 회수에 쓰지 않는다. 아무것도 바꾸지 않는다
    PROMOTION_BALANCE_INSUFFICIENT(HttpStatus.CONFLICT, "프로모션 지갑의 예약되지 않은 HP가 부족합니다."),
    // 쿠폰 예산 예약은 쿠폰당 하나다. 다른 작업 ID로 같은 쿠폰을 다시 예약했다. 아무것도 바꾸지 않는다
    BUDGET_RESERVATION_EXISTS(HttpStatus.CONFLICT, "이 쿠폰의 예산 예약이 이미 있습니다."),
    BUDGET_RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "예산 예약을 찾을 수 없습니다."),
    // 해제는 예약의 남은 예산에서만 한다(보류 중인 예산은 해제하지 않는다). 아무것도 바꾸지 않는다
    BUDGET_RELEASE_EXCEEDS_REMAINING(HttpStatus.CONFLICT, "해제 금액이 예약의 남은 예산보다 많습니다."),
    // 프로모션 보류가 있는 주문 항목은 구매자·플랫폼 재원을 함께 전액 반환·지급한다
    PROMOTION_PARTIAL_NOT_SUPPORTED(HttpStatus.CONFLICT, "프로모션 보류는 전체 반환·지급만 가능합니다."),
    // 주문 항목 보류의 플랫폼 몫이 그 쿠폰 예약의 남은 예산보다 많다. 구매자 보류까지 전부 롤백한다
    PROMOTION_BUDGET_INSUFFICIENT(HttpStatus.CONFLICT, "쿠폰 예산이 부족합니다."),
    // 가상 송금 Mock 은행 API (#288, 로컬·테스트 전용)
    MOCK_PAYOUT_FORBIDDEN(HttpStatus.FORBIDDEN, "가상 송금 API 접근 키가 올바르지 않습니다."),
    MOCK_PAYOUT_INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Idempotency-Key와 지급 요청 ID가 같은 UUID여야 합니다."),
    MOCK_PAYOUT_IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "같은 지급 요청 ID로 다른 내용의 송금이 요청되었습니다."),
    MOCK_PAYOUT_NOT_FOUND(HttpStatus.NOT_FOUND, "가상 송금 요청을 찾을 수 없습니다."),
    MOCK_PAYOUT_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "지금은 가상 송금을 처리할 수 없습니다."),
    // SERVER_ERROR 시나리오(요청을 처리하지 않고 끝난 5xx)
    MOCK_PAYOUT_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "가상 송금 처리 중 오류가 발생했습니다."),
    // 지갑·보류·충전 건 등의 락을 lock_timeout(3초) 안에 얻지 못했다(PostgreSQL 55P03). 트랜잭션은 롤백되어 HP가 움직이지 않았으므로
    // 같은 요청을 다시 보내면 된다 (#273, docs/billing/CONTEXT.md 1.4)
    HP_LOCK_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE, "지금은 HP를 처리할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    // 아래 500 코드는 불변식이 깨진 상황이라 응답 detail에 내부 상태를 드러내지 않고, 원인은 code와 로그로 구분한다.
    CHARGE_PRINCIPAL_MISMATCH(HttpStatus.INTERNAL_SERVER_ERROR, "HP 처리 중 오류가 발생했습니다."),
    HOLD_STATE_INCONSISTENT(HttpStatus.INTERNAL_SERVER_ERROR, "HP 처리 중 오류가 발생했습니다."),
    // PG 승인 결과가 충전 주문과 맞지 않는다(주문·결제키·금액). 충전하지 않고 운영자 확인 대상으로 남긴다 (#224)
    CHARGE_STATE_INCONSISTENT(HttpStatus.INTERNAL_SERVER_ERROR, "HP 처리 중 오류가 발생했습니다."),
    // 환불 요청·충전 건별 취소·보류 기록이 서로 맞지 않는다(완료액 초과 등) (#260)
    REFUND_STATE_INCONSISTENT(HttpStatus.INTERNAL_SERVER_ERROR, "HP 처리 중 오류가 발생했습니다."),
    // 환전 신청·수익 HP 보류 기록이 서로 맞지 않는다(보류 금액·종류 불일치 등) (#285)
    EXCHANGE_STATE_INCONSISTENT(HttpStatus.INTERNAL_SERVER_ERROR, "HP 처리 중 오류가 발생했습니다."),
    // 저장된 환전 계좌번호 암호문을 복호화하지 못했다(암호화 키 없음·키 변경·암호문 손상). 같은 신청인지 판단할 수 없으므로
    // 새 신청·충돌(409)로 보지 않고 거절한다 (#285)
    EXCHANGE_ACCOUNT_UNREADABLE(HttpStatus.INTERNAL_SERVER_ERROR, "HP 처리 중 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String message;
}
