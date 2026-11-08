package com.hapbang.shared.billing.dto;

import java.util.List;
import java.util.Objects;

import com.hapbang.shared.billing.domain.BillingReferenceType;

// 참조 대상별 보류·지급 조회 요청 (#577, 서비스 간 HP 대사). 강의·출연이 자기 대상(주문 항목·요청서·모집글)을 지갑 기록과 비교할 때 쓴다.
// 한 번에 한 참조 유형만, 참조 ID는 최대 MAX_REFERENCE_IDS개다. 이 record는 필수 값·참조 유형·개수 같은 요청 구조만 검사한다.
public record HoldQueryCommand(
        BillingReferenceType referenceType,
        List<Long> referenceIds
) {

    // 한 번에 조회할 수 있는 최대 참조 ID 수
    public static final int MAX_REFERENCE_IDS = 500;

    // 필수 값·보류 가능한 참조 유형·개수(1~500)·빈 ID가 없는지 확인한다.
    public HoldQueryCommand {
        Objects.requireNonNull(referenceType, "참조 유형은 필수입니다.");
        if (!HoldCommand.HOLDABLE_REFERENCE_TYPES.contains(referenceType)) {
            throw new IllegalArgumentException("보류할 수 없는 참조 유형입니다: " + referenceType);
        }
        Objects.requireNonNull(referenceIds, "참조 ID 목록은 필수입니다.");
        if (referenceIds.isEmpty() || referenceIds.size() > MAX_REFERENCE_IDS) {
            throw new IllegalArgumentException("참조 ID는 1~" + MAX_REFERENCE_IDS + "개여야 합니다: " + referenceIds.size());
        }
        referenceIds = List.copyOf(referenceIds);
    }
}
