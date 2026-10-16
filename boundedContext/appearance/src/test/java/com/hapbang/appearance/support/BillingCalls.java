package com.hapbang.appearance.support;

import java.util.ArrayList;
import java.util.List;

import org.mockito.Mockito;
import org.mockito.invocation.Invocation;

import com.hapbang.appearance.out.billing.BillingClient;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.dto.PenaltyCommand;
import com.hapbang.shared.billing.dto.ReleaseCommand;

/**
 * Billing 대역에 보낸 요청을 순서대로 꺼낸다 (#289). 실패로 끝난 요청(대역이 예외를 던진 호출)도 포함한다.
 * 잔액·보류 남은 금액은 Billing이 정하므로 여기서 계산하지 않는다 (apps 통합 테스트가 확인).
 */
public final class BillingCalls {

    private BillingCalls() {
    }

    public static List<HoldCommand> holds(BillingClient billingClient) {
        return argumentsOf(billingClient, "hold", HoldCommand.class);
    }

    public static List<ReleaseCommand> releases(BillingClient billingClient) {
        return argumentsOf(billingClient, "release", ReleaseCommand.class);
    }

    public static List<PayoutCommand> payouts(BillingClient billingClient) {
        return argumentsOf(billingClient, "payout", PayoutCommand.class);
    }

    public static List<PenaltyCommand> penalties(BillingClient billingClient) {
        return argumentsOf(billingClient, "settlePenalty", PenaltyCommand.class);
    }

    /**
     * 이 보류 대상에 Billing이 받아들인 반환 HP 합계 (대역이 실패로 설정돼 예외를 던진 요청은 빼고 센다).
     * 남은 보류 금액은 Billing이 정하므로, 출연 테스트는 "얼마를 돌려 달라고 했는지"만 본다.
     */
    public static long releasedAmount(BillingClient billingClient, BillingReferenceType referenceType,
                                      Long referenceId) {
        long total = 0;
        for (Invocation invocation : Mockito.mockingDetails(billingClient).getInvocations()) {
            if (!invocation.getMethod().getName().equals("release") || invocation.stubInfo() != null) {
                continue;
            }
            ReleaseCommand release = invocation.getArgument(0);
            if (release.referenceType() == referenceType && release.referenceId().equals(referenceId)) {
                total += release.amountHp();
            }
        }
        return total;
    }

    /** 보낸 요청이 하나도 없는지 (보류·반환·지급·위약금 모두) */
    public static boolean none(BillingClient billingClient) {
        return Mockito.mockingDetails(billingClient).getInvocations().isEmpty();
    }

    private static <T> List<T> argumentsOf(BillingClient billingClient, String method, Class<T> type) {
        List<T> commands = new ArrayList<>();
        for (Invocation invocation : Mockito.mockingDetails(billingClient).getInvocations()) {
            if (invocation.getMethod().getName().equals(method)) {
                commands.add(type.cast(invocation.getArgument(0)));
            }
        }
        return commands;
    }
}
