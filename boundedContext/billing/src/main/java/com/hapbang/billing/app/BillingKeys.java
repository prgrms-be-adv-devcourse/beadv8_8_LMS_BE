package com.hapbang.billing.app;

import java.util.UUID;

import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.dto.PenaltyCommand;
import com.hapbang.shared.billing.dto.ReleaseCommand;

// 보류·반환·지급·위약금 정산의 멱등키(원장 작업 키)를 요청으로 결정론적으로 만든다.
// 키 형식은 Billing 내부 규칙이라 shared 요청 DTO에 두지 않는다(호출한 쪽은 키를 만들거나 넘기지 않는다, #145).
// 형식은 #78·#100·#134에서 정한 그대로이며, 바꾸면 기존 보류·상세·지급 건·원장을 다시 찾을 수 없으므로 바꾸지 않는다.
// 예외: PG 취소 멱등키(refundCancel)는 외부(TossPayments 상점 전체)에서 고유해야 해서 무작위로 만들고 DB에 저장해 다시 쓴다.
final class BillingKeys {

    static final String HOLD_PREFIX = "HOLD";
    static final String RELEASE_PREFIX = "RELEASE";
    static final String PAYOUT_PREFIX = "PAYOUT";
    static final String PENALTY_PREFIX = "PENALTY";
    static final String CHARGE_PREFIX = "CHARGE";
    static final String CAPTURE_PREFIX = "CAPTURE";
    static final String REFUND_PREFIX = "REFUND";
    static final String REFUND_CANCEL_PREFIX = "hapbang-refund-cancel-";
    static final String REFUND_HOLD_REQUEST_PREFIX = "REFUND-";

    private BillingKeys() {
    }

    // 참조 대상의 보류 멱등키 HOLD:{referenceType}:{referenceId}. 원장 작업 키로도 같은 값을 쓴다.
    // 참조 유형은 Enum 이름으로만 표기하므로 같은 대상이 다른 문자열로 표기되어 보류가 중복 생성되지 않는다.
    static String hold(BillingReferenceType referenceType, Long referenceId) {
        return HOLD_PREFIX + ":" + referenceType.name() + ":" + referenceId;
    }

    static String hold(HoldCommand command) {
        return hold(command.referenceType(), command.referenceId());
    }

    static String hold(ReleaseCommand command) {
        return hold(command.referenceType(), command.referenceId());
    }

    static String hold(PayoutCommand command) {
        return hold(command.referenceType(), command.referenceId());
    }

    static String hold(PenaltyCommand command) {
        return hold(command.referenceType(), command.referenceId());
    }

    // 충전 원장 작업 키 CHARGE:{chargeId}. 충전 건 하나에 충전 원장은 한 번만 기록된다 (#224).
    static String charge(Long chargeId) {
        return CHARGE_PREFIX + ":" + chargeId;
    }

    // 반환 멱등키 RELEASE:{holdId}:{releaseRequestId}. Billing 내부 반환(#241)도 같은 형식이다.
    static String release(Long holdId, String releaseRequestId) {
        return RELEASE_PREFIX + ":" + holdId + ":" + releaseRequestId;
    }

    // 확정 차감 멱등키 CAPTURE:{holdId}:{captureRequestId}. 확정 차감 상세 멱등키이자 원장 작업 키다 (#241).
    static String capture(Long holdId, String captureRequestId) {
        return CAPTURE_PREFIX + ":" + holdId + ":" + captureRequestId;
    }

    // 회원 환불 요청 멱등키 REFUND:{walletId}:{클라이언트 Idempotency-Key}. 같은 회원의 같은 키만 같은 요청이다 (#260).
    static String refundRequest(Long walletId, String clientKey) {
        return REFUND_PREFIX + ":" + walletId + ":" + clientKey;
    }

    // 충전 건 하나의 PG 취소 멱등키 hapbang-refund-cancel-{UUID}. 개별 환불을 만들 때 한 번 만들어 point_refund.idempotency_key
    // (UNIQUE)에 저장하고, TossPayments Idempotency-Key와 취소 사유 표식에 그 저장값을 쓴다. 결과를 모를 때도 저장값으로 다시 요청·조회한다.
    // DB ID로 만들지 않는다: DB 초기화나 같은 TossPayments 상점을 쓰는 다른 환경에서 같은 ID가 다시 나오면, TossPayments가 다른 결제의
    // 이전 응답을 돌려주거나(멱등키 15일) 다른 결제의 취소를 우리 취소로 오인할 수 있다 (#260).
    static String refundCancel() {
        return REFUND_CANCEL_PREFIX + UUID.randomUUID();
    }

    // 개별 환불의 보류 확정 차감·반환 요청 ID REFUND-{refundId}. 확정 차감(CAPTURE:)과 반환(RELEASE:)은 접두사가 달라
    // 개별 환불 하나는 둘 중 하나로만 한 번 처리된다 (#260).
    static String refundHoldRequestId(Long refundId) {
        return REFUND_HOLD_REQUEST_PREFIX + refundId;
    }

    // 반환 멱등키 RELEASE:{holdId}:{releaseRequestId}. 반환 상세 멱등키이자 원장 작업 키다.
    static String release(Long holdId, ReleaseCommand command) {
        return RELEASE_PREFIX + ":" + holdId + ":" + command.releaseRequestId();
    }

    // 지급 키 PAYOUT:{holdId}:{payoutRequestId}. 지급 상세·지급 건(Settlement)의 멱등키이자 원장 작업 키다.
    static String payout(Long holdId, PayoutCommand command) {
        return PAYOUT_PREFIX + ":" + holdId + ":" + command.payoutRequestId();
    }

    // 취소 사건 하나의 정산 키 PENALTY:{holdId}:{cancellationId}. 위약금 지급 건(Settlement)의 멱등키다.
    static String penalty(Long holdId, PenaltyCommand command) {
        return PENALTY_PREFIX + ":" + holdId + ":" + command.cancellationId();
    }

    // 이 취소 사건의 호스트 반환 상세 멱등키이자 반환 원장 작업 키
    static String penaltyRelease(Long holdId, PenaltyCommand command) {
        return penalty(holdId, command) + ":RELEASE";
    }

    // 이 취소 사건의 게스트 위약금 지급 상세 멱등키이자 지급 원장 작업 키
    static String penaltyPayout(Long holdId, PenaltyCommand command) {
        return penalty(holdId, command) + ":PAYOUT";
    }
}
