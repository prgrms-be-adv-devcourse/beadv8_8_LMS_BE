package com.hapbang.billing.app;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.dto.PenaltyCommand;
import com.hapbang.shared.billing.dto.ReleaseCommand;

// shared 요청 DTO는 구조만 검사하므로, 금액 같은 금융 값은 각 UseCase가 처리를 시작할 때 여기서 BillingException으로 거절한다(#145).
// 오류 코드·HTTP 상태는 요청 DTO가 billing에 있을 때와 같다(INVALID_POINT_AMOUNT 400, PENALTY_AMOUNT_ZERO 400).
// 잔액·보류 소유·남은 보류 초과·지급 원천·자기 지급·멱등성처럼 저장된 상태가 필요한 검증은 UseCase가 한다.
final class BillingCommandValidator {

    private BillingCommandValidator() {
    }

    // 보류 금액이 0 이하이면 INVALID_POINT_AMOUNT로 거절한다.
    static void verify(HoldCommand command) {
        requirePositive(command.amountHp());
    }

    // 부분 반환 금액이 0 이하이면 INVALID_POINT_AMOUNT로 거절한다. 금액이 없으면 전체 반환이다.
    static void verify(ReleaseCommand command) {
        if (!command.isFull()) {
            requirePositive(command.amountHp());
        }
    }

    // 부분 지급 금액이 0 이하이면 INVALID_POINT_AMOUNT로 거절한다. 금액이 없으면 전체 지급이다.
    static void verify(PayoutCommand command) {
        if (!command.isFull()) {
            requirePositive(command.amountHp());
        }
    }

    // 호스트 반환액은 0 이상, 게스트 지급액(위약금)은 1 이상이어야 한다. 음수는 INVALID_POINT_AMOUNT, 위약금 0은 PENALTY_AMOUNT_ZERO로 거절한다.
    // 반환액 0은 위약금이 정산 기준액 전부인 경우로 받으며, 그때는 반환 상세·원장을 만들지 않는다(0 HP 원장 금지).
    // 두 금액의 합(정산 기준액)이 long 범위를 넘으면 INVALID_POINT_AMOUNT로 거절한다. 위약금 비율은 출연이 정하므로 검사하지 않는다.
    static long verify(PenaltyCommand command) {
        if (command.releaseAmountHp() < 0 || command.payoutAmountHp() < 0) {
            throw new BillingException(BillingErrorCode.INVALID_POINT_AMOUNT);
        }
        if (command.payoutAmountHp() == 0) {
            throw new BillingException(BillingErrorCode.PENALTY_AMOUNT_ZERO);
        }
        return settlementAmountHp(command);
    }

    // 정산 기준액 = 호스트 반환액 + 게스트 지급액
    static long settlementAmountHp(PenaltyCommand command) {
        try {
            return Math.addExact(command.releaseAmountHp(), command.payoutAmountHp());
        } catch (ArithmeticException exception) {
            throw new BillingException(BillingErrorCode.INVALID_POINT_AMOUNT, exception);
        }
    }

    private static void requirePositive(long amountHp) {
        if (amountHp <= 0) {
            throw new BillingException(BillingErrorCode.INVALID_POINT_AMOUNT);
        }
    }
}
