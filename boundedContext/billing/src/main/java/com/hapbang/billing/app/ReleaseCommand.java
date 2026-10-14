package com.hapbang.billing.app;

import java.util.Objects;
import java.util.regex.Pattern;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.BillingReferenceType;

// 참조 대상 하나의 보류에서 HP 일부(부분 반환) 또는 남은 보류 전부(전체 반환)를 결제한 회원에게 되돌리는 요청이다.
// 반환 요청 ID는 강의·출연이 업무 요청마다 안정적으로 만들어 재전송 때 같은 값을 쓴다. 호출할 때마다 새 UUID를 만들지 않는다.
// 반환 요청 ID는 보류 하나 안에서 고유하다. 다른 보류에서 같은 ID를 쓰면 반환 키가 달라 별개 요청이다.
public record ReleaseCommand(
        Long userId,
        BillingReferenceType referenceType,
        Long referenceId,
        String releaseRequestId,
        Long amountHp
) {

    // 반환 멱등키와 원장 작업 키의 접두사
    static final String KEY_PREFIX = "RELEASE";

    // 반환 요청 ID에 쓸 수 있는 문자. ':'는 키 구분자라 쓸 수 없고, 영문·숫자·'-'·'_'만 허용한다 (예: RECRUIT-7-RELEASE-UNDERFILLED-1).
    // 길이는 원장 멱등키 RELEASE:{holdId}:{releaseRequestId}:{줄번호}가 컬럼 길이 255자를 넘지 않도록 제한한다.
    static final Pattern RELEASE_REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{1,200}");

    // 필수 값·반환 요청 ID 형식을 확인하고, 부분 반환 금액이 0 이하이면 INVALID_POINT_AMOUNT로 거절한다. 금액이 null이면 전체 반환이다.
    public ReleaseCommand {
        Objects.requireNonNull(userId, "회원 ID는 필수입니다.");
        Objects.requireNonNull(referenceType, "참조 유형은 필수입니다.");
        Objects.requireNonNull(referenceId, "참조 ID는 필수입니다.");
        if (!HoldCommand.HOLDABLE_REFERENCE_TYPES.contains(referenceType)) {
            throw new IllegalArgumentException("보류할 수 없는 참조 유형입니다: " + referenceType);
        }
        if (releaseRequestId == null || !RELEASE_REQUEST_ID.matcher(releaseRequestId).matches()) {
            throw new IllegalArgumentException("반환 요청 ID 형식이 올바르지 않습니다: " + releaseRequestId);
        }
        if (amountHp != null && amountHp <= 0) {
            throw new BillingException(BillingErrorCode.INVALID_POINT_AMOUNT);
        }
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
    public boolean isFull() {
        return amountHp == null;
    }

    // 반환할 보류를 찾는 보류 멱등키 HOLD:{referenceType}:{referenceId}를 만든다.
    String holdKey() {
        return HoldCommand.holdKey(referenceType, referenceId);
    }

    // 보류 ID와 반환 요청 ID로 결정론적인 반환 멱등키 RELEASE:{holdId}:{releaseRequestId}를 만든다. 원장 작업 키로도 같은 값을 쓴다.
    String idempotencyKey(Long holdId) {
        return KEY_PREFIX + ":" + holdId + ":" + releaseRequestId;
    }
}
