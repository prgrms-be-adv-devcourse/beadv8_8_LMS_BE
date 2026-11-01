package com.hapbang.shared.billing.dto;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.hapbang.shared.billing.domain.BillingReferenceType;

// 참조 대상 하나의 보류에서 HP 일부(부분 반환) 또는 남은 보류 전부(전체 반환)를 결제한 회원에게 되돌리는 요청이다 (내부 API releases).
// 반환 요청 ID는 강의·출연이 업무 요청마다 안정적으로 만들어 재전송 때 같은 값을 쓴다. 호출할 때마다 새 UUID를 만들지 않는다.
// 반환 요청 ID는 보류 하나 안에서 고유하다. 다른 보류에서 같은 ID를 쓰면 별개 요청이다.
// 이 record는 요청 구조만 검사한다. 금액(0 이하 거절)·보류 소유·남은 보류 초과·멱등성은 Billing이 BillingException으로 검증한다.
public record ReleaseCommand(
        Long userId,
        BillingReferenceType referenceType,
        Long referenceId,
        String releaseRequestId,
        Long amountHp
) {

    // 필수 값·반환 요청 ID 형식을 확인한다. 금액이 null이면 전체 반환이다.
    public ReleaseCommand {
        Objects.requireNonNull(userId, "회원 ID는 필수입니다.");
        BillingRequests.requireHoldable(referenceType, referenceId);
        BillingRequests.requireRequestId(releaseRequestId, "반환 요청 ID");
    }

    // 남은 보류액 중 요청한 금액만 반환하는 부분 반환 요청을 만든다.
    public static ReleaseCommand partial(Long userId, BillingReferenceType referenceType, Long referenceId,
            String releaseRequestId, long amountHp) {
        return new ReleaseCommand(userId, referenceType, referenceId, releaseRequestId, amountHp);
    }

    // 처리 시점에 남아 있는 보류액 전부를 반환하는 전체 반환 요청을 만든다.
    public static ReleaseCommand full(Long userId, BillingReferenceType referenceType, Long referenceId,
            String releaseRequestId) {
        return new ReleaseCommand(userId, referenceType, referenceId, releaseRequestId, null);
    }

    // 금액 없이 남은 보류 전부를 반환하는 요청인지 확인한다.
    // 요청 본문(JSON)에 넣지 않는다. 금액이 null인 것으로 충분하다 (#272)
    @JsonIgnore
    public boolean isFull() {
        return amountHp == null;
    }
}
