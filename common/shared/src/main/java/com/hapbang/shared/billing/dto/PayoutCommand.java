package com.hapbang.shared.billing.dto;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.SettlementSourceType;

// 참조 대상 하나의 보류에서 HP 일부(부분 지급) 또는 남은 보류 전부(전체 지급)를 받는 회원의 수익 HP로 옮기는 정상 지급 요청이다 (내부 API payouts).
// 강의·출연이 지급 가능 여부와 금액을 정한다. 지급 원천은 출연 계약·주문 항목 단위다(1:1 = 출연 계약 ID, 1:N = 게스트별 출연 계약 ID, 강의 = 주문 항목 ID).
// 지급 요청 ID는 보류 하나 안에서 고유하며, 같은 업무 요청을 다시 보낼 때는 같은 값을 쓴다(호출마다 새 UUID를 만들지 않는다).
// 이 record는 요청 구조만 검사한다. 금액·지갑·보류 소유·지급 원천·자기 지급·중복 정산·멱등성은 Billing이 BillingException으로 검증한다.
// 출처 제목(sourceTitle)은 지급 완료 이벤트에 담는 선택 값이다 (#873, 규칙은 지갑 BD-163).
public record PayoutCommand(
        Long payerUserId,
        BillingReferenceType referenceType,
        Long referenceId,
        String payoutRequestId,
        Long receiverUserId,
        SettlementSourceType sourceType,
        Long sourceId,
        Long amountHp,
        // 제목이 없으면 본문에 쓰지 않는다(#873 이전 본문과 같다)
        @JsonInclude(JsonInclude.Include.NON_NULL) String sourceTitle
) {

    // 필수 값·지급 요청 ID 형식을 확인한다. 금액이 null이면 전체 지급이다. 출처 제목은 앞뒤 공백을 지우고 빈 값은 null로 둔다.
    public PayoutCommand {
        Objects.requireNonNull(payerUserId, "보류한 회원 ID는 필수입니다.");
        Objects.requireNonNull(receiverUserId, "받는 회원 ID는 필수입니다.");
        Objects.requireNonNull(sourceType, "지급 원천 유형은 필수입니다.");
        Objects.requireNonNull(sourceId, "지급 원천 ID는 필수입니다.");
        BillingRequests.requireHoldable(referenceType, referenceId);
        BillingRequests.requireRequestId(payoutRequestId, "지급 요청 ID");
        sourceTitle = BillingRequests.normalizeTitle(sourceTitle);
    }

    // 출처 제목 없이 만든다 (#873 이전 호출과 같다).
    public PayoutCommand(Long payerUserId, BillingReferenceType referenceType, Long referenceId, String payoutRequestId,
            Long receiverUserId, SettlementSourceType sourceType, Long sourceId, Long amountHp) {
        this(payerUserId, referenceType, referenceId, payoutRequestId, receiverUserId, sourceType, sourceId, amountHp,
                null);
    }

    // 남은 보류액 중 요청한 금액만 지급하는 부분 지급 요청을 만든다.
    public static PayoutCommand partial(Long payerUserId, BillingReferenceType referenceType, Long referenceId,
            String payoutRequestId, Long receiverUserId, SettlementSourceType sourceType, Long sourceId,
            long amountHp) {
        return partial(payerUserId, referenceType, referenceId, payoutRequestId, receiverUserId, sourceType, sourceId,
                amountHp, null);
    }

    // 출처 제목을 붙인 부분 지급 요청을 만든다 (#873).
    public static PayoutCommand partial(Long payerUserId, BillingReferenceType referenceType, Long referenceId,
            String payoutRequestId, Long receiverUserId, SettlementSourceType sourceType, Long sourceId,
            long amountHp, String sourceTitle) {
        return new PayoutCommand(payerUserId, referenceType, referenceId, payoutRequestId, receiverUserId,
                sourceType, sourceId, amountHp, sourceTitle);
    }

    // 처리 시점에 남아 있는 보류액 전부를 지급하는 전체 지급 요청을 만든다.
    public static PayoutCommand full(Long payerUserId, BillingReferenceType referenceType, Long referenceId,
            String payoutRequestId, Long receiverUserId, SettlementSourceType sourceType, Long sourceId) {
        return full(payerUserId, referenceType, referenceId, payoutRequestId, receiverUserId, sourceType, sourceId,
                null);
    }

    // 출처 제목을 붙인 전체 지급 요청을 만든다 (#873).
    public static PayoutCommand full(Long payerUserId, BillingReferenceType referenceType, Long referenceId,
            String payoutRequestId, Long receiverUserId, SettlementSourceType sourceType, Long sourceId,
            String sourceTitle) {
        return new PayoutCommand(payerUserId, referenceType, referenceId, payoutRequestId, receiverUserId,
                sourceType, sourceId, null, sourceTitle);
    }

    // 금액 없이 남은 보류 전부를 지급하는 요청인지 확인한다.
    // 요청 본문(JSON)에 넣지 않는다. 금액이 null인 것으로 충분하다 (#272)
    @JsonIgnore
    public boolean isFull() {
        return amountHp == null;
    }
}
