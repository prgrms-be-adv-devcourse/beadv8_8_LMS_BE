package com.hapbang.shared.billing.dto;

import java.util.Objects;
import java.util.regex.Pattern;

import com.hapbang.shared.billing.domain.BillingReferenceType;

// Billing 요청 DTO가 함께 쓰는 구조 검사다. 금융 정책은 검사하지 않는다.
// 구조 오류는 호출한 쪽 코드의 오류이므로 업무 예외가 아니라 NullPointerException·IllegalArgumentException으로 알린다
// (global 공통 핸들러는 처리하지 못한 예외로 500 INTERNAL_ERROR를 응답한다. 이동 전 Billing 요청과 같다).
final class BillingRequests {

    // 반환·지급 요청 ID와 취소 ID에 쓸 수 있는 문자. ':'는 Billing 키 구분자라 쓸 수 없고, 영문·숫자·'-'·'_'만 허용한다
    // (예: RECRUIT-7-RELEASE-UNDERFILLED-1). 길이는 Billing 원장 멱등키({키}:{요청 ID}:...:{줄번호})가 컬럼 길이 255자를 넘지 않도록 제한한다.
    static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{1,200}");

    private BillingRequests() {
    }

    // 참조 유형·참조 ID가 있고 보류를 만들 수 있는 참조 유형인지 확인한다.
    static void requireHoldable(BillingReferenceType referenceType, Long referenceId) {
        Objects.requireNonNull(referenceType, "참조 유형은 필수입니다.");
        Objects.requireNonNull(referenceId, "참조 ID는 필수입니다.");
        if (!HoldCommand.HOLDABLE_REFERENCE_TYPES.contains(referenceType)) {
            throw new IllegalArgumentException("보류할 수 없는 참조 유형입니다: " + referenceType);
        }
    }

    // 요청 ID가 있고 형식에 맞는지 확인한다.
    static void requireRequestId(String requestId, String name) {
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
            throw new IllegalArgumentException(name + " 형식이 올바르지 않습니다: " + requestId);
        }
    }
}
