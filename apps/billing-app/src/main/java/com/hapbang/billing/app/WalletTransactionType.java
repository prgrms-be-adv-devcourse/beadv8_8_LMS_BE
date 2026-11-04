package com.hapbang.billing.app;

import com.hapbang.billing.out.LedgerOperationSum;
import com.hapbang.shared.billing.domain.BillingReferenceType;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 내 HP 거래 내역(#295)에 보여 줄 사용자용 거래 유형과 표시 이름이다. 상수 이름은 응답 문자열이므로 바꾸지 않는다.
// 원장 거래 유형·참조 대상·이 지갑의 증감 방향으로 정한다. 보류(가용 → 보류)와 확정(보류 차감)은 서로 다른 거래다.
@Getter
@RequiredArgsConstructor
public enum WalletTransactionType {
    CHARGE("HP 충전"),
    /** 강의·출연 결제로 가용 HP를 보류했다. 총액은 그대로다 */
    PAYMENT_HOLD("결제 보류"),
    /** 결제 보류를 결제한 회원에게 되돌렸다(구매 취소·거절·취소, 위약금 정산의 호스트 반환) */
    PAYMENT_RELEASE("결제 보류 해제"),
    /** 보류한 결제 대금을 판매자·게스트에게 지급해 보류 HP가 줄었다 */
    PAYMENT_SETTLED("결제 지급 확정"),
    /** 호스트 일방 취소 위약금을 게스트에게 지급해 보류 HP가 줄었다 */
    PENALTY_PAID("위약금 지급 확정"),
    /** 강의 판매 대금·출연료를 수익 HP로 받았다 */
    EARNING_RECEIVED("수익 HP 지급"),
    /** 호스트 일방 취소 위약금을 수익 HP로 받았다 */
    PENALTY_RECEIVED("위약금 수령"),
    /** 충전 HP 환불 신청으로 충전 HP를 보류했다 */
    REFUND_HOLD("환불 신청 보류"),
    /** 환불하지 못한 충전 HP 보류를 되돌렸다 */
    REFUND_RELEASE("환불 보류 해제"),
    /** 충전 HP 환불이 확정되어 보류 HP가 줄었다 */
    REFUND("충전 HP 환불"),
    /** 환전 신청으로 수익 HP를 보류했다 */
    EXCHANGE_HOLD("환전 신청 보류"),
    /** 환전 취소·거절·최종 실패로 수익 HP 보류를 되돌렸다 */
    EXCHANGE_RELEASE("환전 보류 해제"),
    /** 환전 송금이 확정되어 보류 HP가 줄었다 */
    EXCHANGE("수익 HP 환전"),
    /** 잘못 기록한 원장을 반대 부호로 정정했다 */
    REVERSAL("원장 정정");

    private final String displayName;

    // 이 지갑의 작업 합계로 거래 유형을 정한다. 지급(PAYOUT)은 한 작업이 두 지갑에 걸치므로, 보류가 줄었으면 보낸 쪽이고
    // 아니면(수익 HP 가용 증가) 받는 쪽이다. 위약금은 작업 키 형식 PENALTY:{holdId}:{cancellationId}:PAYOUT으로 구분한다
    // (BillingKeys: 형식을 바꾸지 않는 원장 작업 키 규칙, 위약금 Settlement의 멱등키가 이 접두사다).
    static WalletTransactionType of(LedgerOperationSum sum) {
        return switch (sum.transactionType()) {
            case CHARGE -> CHARGE;
            case HOLD -> byReference(sum.referenceType(), PAYMENT_HOLD, REFUND_HOLD, EXCHANGE_HOLD);
            case RELEASE -> byReference(sum.referenceType(), PAYMENT_RELEASE, REFUND_RELEASE, EXCHANGE_RELEASE);
            case PAYOUT -> payout(sum);
            case REFUND -> REFUND;
            case EXCHANGE -> EXCHANGE;
            // 플랫폼 지갑에만 있고, 플랫폼 지갑은 회원 거래 내역에서 조회되지 않는다 (#336)
            case EXCHANGE_FEE -> throw new IllegalStateException("회원 지갑에는 환전 수수료 원장이 없습니다.");
            case REVERSAL -> REVERSAL;
        };
    }

    private static WalletTransactionType payout(LedgerOperationSum sum) {
        boolean penalty = sum.operationKey().startsWith(BillingKeys.PENALTY_PREFIX + ":");
        boolean sent = sum.chargeHeldHp() != 0 || sum.revenueHeldHp() != 0;
        if (sent) {
            return penalty ? PENALTY_PAID : PAYMENT_SETTLED;
        }
        return penalty ? PENALTY_RECEIVED : EARNING_RECEIVED;
    }

    private static WalletTransactionType byReference(BillingReferenceType referenceType,
            WalletTransactionType payment, WalletTransactionType refund, WalletTransactionType exchange) {
        return switch (referenceType) {
            case POINT_REFUND_REQUEST -> refund;
            case EXCHANGE -> exchange;
            default -> payment;
        };
    }
}
