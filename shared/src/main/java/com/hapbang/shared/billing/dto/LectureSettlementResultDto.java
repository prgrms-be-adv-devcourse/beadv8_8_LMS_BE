package com.hapbang.shared.billing.dto;

import java.time.Instant;

// 강의 판매 수익 지급 예정 건 등록·취소 결과 (LectureSettlementPort). payableAt은 Billing이 정한 지급 가능 시각
// (구매일 KST + 31일 00:00 KST)이다. replayed가 true면 같은 요청을 다시 보내 아무것도 바꾸지 않은 것이다.
// Billing 내부 정보(지급 건·보류·지갑 ID, 내부 상태 값)는 담지 않는다.
public record LectureSettlementResultDto(
        Long orderItemId,
        Long sellerUserId,
        long amountHp,
        Instant payableAt,
        boolean replayed
) {
}
