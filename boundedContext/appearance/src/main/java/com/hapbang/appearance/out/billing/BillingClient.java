package com.hapbang.appearance.out.billing;

import java.util.List;
import java.util.Objects;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 지갑+정산(billing) 모듈과의 HP 연동 창구. 출연 모듈은 이 인터페이스로만 Billing을 호출한다.
 * <p>
 * 이 파일 하나에 Billing과 주고받는 값(요청·응답·사유)을 모두 모아 두었다. Billing 담당과 맞출 계약이 곧 이 파일이다.
 * <ul>
 *     <li>모든 요청은 멱등 키를 가진다. 같은 키로 다시 요청해도 한 번만 처리된다 (타임아웃 후 재시도용).</li>
 *     <li>보류는 요청서·모집글 단위로 1건이고, 반환·지급은 그 보류에서 여러 번 나눠서 처리할 수 있다.</li>
 *     <li>멱등 키 형식: {@code {refType}-{refId}-{동작}-{순번}} (예: {@code APPEARANCE_REQUEST-12-HOLD-1}). {@link #idempotencyKey}로 만든다.</li>
 * </ul>
 * 구현체는 {@code appearance.billing.mode} 값으로 고른다. 지금은 {@link FakeBillingClient}만 있다.
 */
// TODO(billing): Billing API 계약이 확정되면 HTTP 구현체를 추가한다.
public interface BillingClient {

    /**
     * 호스트 잔액에서 HP를 보류한다. 잔액이 부족하면 예외 대신 {@code INSUFFICIENT_BALANCE}를 돌려준다.
     *
     * @throws BillingUnavailableException Billing이 응답하지 않을 때 (처리 여부를 알 수 없음. 같은 멱등 키로 다시 확인한다)
     */
    HoldResult hold(HoldCommand command);

    /**
     * 보류 HP를 호스트에게 반환한다 (차감했던 HP 종류 그대로).
     */
    Result release(ReleaseCommand command);

    /**
     * 보류 HP를 게스트의 수익 HP로 지급한다.
     */
    Result payout(PayoutCommand command);

    /**
     * 같은 보류에서 여러 건의 지급·반환을 한 번에 처리한다. 전부 성공하거나 전부 실패한다.
     * 예: 호스트 일방 취소(위약금 지급 + 나머지 반환), 모집 전체 취소.
     */
    Result settle(SettleCommand command);

    /**
     * 멱등 키를 만든다. 형식: {@code {refType}-{refId}-{동작}-{순번}} (예: {@code APPEARANCE_REQUEST-12-HOLD-1})
     */
    static String idempotencyKey(RefType refType, long refId, String action, int sequence) {
        return "%s-%d-%s-%d".formatted(refType.name(), refId, action, sequence);
    }

    // ---------------------------------------------------------------------------------------------
    // 주고받는 값
    // ---------------------------------------------------------------------------------------------

    /**
     * HP 보류의 기준이 되는 출연 쪽 대상.
     */
    @Getter
    @RequiredArgsConstructor
    enum RefType {
        APPEARANCE_REQUEST("출연 요청서. 출연료만큼 보류한다"),
        APPEARANCE_RECRUIT("출연 모집글. 출연료 × 모집 인원만큼 보류한다");

        private final String description;
    }

    /**
     * 반환·지급 사유. Billing 원장에 거래 사유로 남는다.
     */
    @Getter
    @RequiredArgsConstructor
    enum Reason {
        // 반환 (보류 HP → 호스트)
        REQUEST_REJECTED("게스트가 출연 요청을 거절"),
        REQUEST_EXPIRED("수락 기한까지 게스트 응답 없음"),
        REQUEST_CANCELED("게스트 응답 전에 호스트가 출연 요청을 취소"),
        CANCEL_AGREED("게스트의 출연 취소 요청을 호스트가 수락 (합의 취소, 위약금 없음)"),
        NO_SHOW("출연 확인 부정 응답 (노쇼)"),
        RECRUIT_UNDERFILLED("모집 마감 시 미달 인원분"),
        RECRUIT_CANCELED("모집 취소 (성립 인원 없음 또는 빈자리분)"),
        HOST_CANCELED("호스트 일방 취소 시 위약금을 뺀 나머지"),

        // 지급 (보류 HP → 게스트 수익 HP)
        APPEARANCE_CONFIRMED("출연 확인 (긍정 응답 또는 D+7 자동 확인)"),
        HOST_CANCEL_PENALTY("호스트 일방 취소 위약금 (출연료의 30%)");

        private final String description;
    }

    record HoldCommand(String idempotencyKey, RefType refType, long refId, long hostId, long amount) {

        public HoldCommand {
            requireKey(idempotencyKey);
            requireRef(refType, refId);
            requirePositive(hostId, "호스트 ID");
            requirePositive(amount, "금액");
        }
    }

    record ReleaseCommand(String idempotencyKey, RefType refType, long refId, long hostId, long amount,
                          Reason reason) {

        public ReleaseCommand {
            requireKey(idempotencyKey);
            requireRef(refType, refId);
            requirePositive(hostId, "호스트 ID");
            requirePositive(amount, "금액");
            Objects.requireNonNull(reason, "반환 사유는 필수입니다.");
        }
    }

    record PayoutCommand(String idempotencyKey, RefType refType, long refId, long appearanceId, long guestId,
                         long amount, Reason reason) {

        public PayoutCommand {
            requireKey(idempotencyKey);
            requireRef(refType, refId);
            requirePositive(appearanceId, "출연 ID");
            requirePositive(guestId, "게스트 ID");
            requirePositive(amount, "금액");
            Objects.requireNonNull(reason, "지급 사유는 필수입니다.");
        }
    }

    record SettleCommand(String idempotencyKey, RefType refType, long refId, List<SettleItem> items) {

        public SettleCommand {
            requireKey(idempotencyKey);
            requireRef(refType, refId);
            if (items == null || items.isEmpty()) {
                throw new IllegalArgumentException("정산 항목이 비어 있습니다.");
            }
            items = List.copyOf(items);
        }

        public long totalAmount() {
            long total = 0;
            for (SettleItem item : items) {
                total += item.amount();
            }
            return total;
        }
    }

    /**
     * 일괄 정산 항목 하나.
     *
     * @param memberId     받는 사람 (지급이면 게스트, 반환이면 호스트)
     * @param appearanceId 관련 출연 ID. 빈자리 반환처럼 출연이 없으면 null
     */
    record SettleItem(SettleType type, long memberId, Long appearanceId, long amount, Reason reason) {

        public SettleItem {
            Objects.requireNonNull(type, "정산 항목 타입은 필수입니다.");
            requirePositive(memberId, "회원 ID");
            requirePositive(amount, "금액");
            Objects.requireNonNull(reason, "정산 사유는 필수입니다.");
        }
    }

    @Getter
    @RequiredArgsConstructor
    enum SettleType {
        PAYOUT("게스트에게 지급"),
        RELEASE("호스트에게 반환");

        private final String description;
    }

    /**
     * 보류 결과.
     *
     * @param heldAmount 보류 후 이 대상에 묶여 있는 총액. 잔액 부족이면 0
     */
    record HoldResult(HoldStatus status, long heldAmount) {

        public boolean isHeld() {
            return status == HoldStatus.HELD;
        }
    }

    @Getter
    @RequiredArgsConstructor
    enum HoldStatus {
        HELD("보류 완료"),
        INSUFFICIENT_BALANCE("잔액 부족으로 보류하지 않음 (예외가 아니라 결과로 돌려준다)");

        private final String description;
    }

    /**
     * 반환·지급·일괄 정산 결과.
     *
     * @param remainingHeldAmount 처리 후 이 대상에 남아 있는 보류 금액
     */
    record Result(long remainingHeldAmount) {
    }

    // ---------------------------------------------------------------------------------------------
    // 요청 값 검사: 잘못된 값이 Billing까지 가기 전에 출연 모듈에서 막는다.
    // ---------------------------------------------------------------------------------------------

    private static void requireKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("멱등 키는 비어 있을 수 없습니다.");
        }
    }

    private static void requireRef(RefType refType, long refId) {
        Objects.requireNonNull(refType, "참조 타입은 필수입니다.");
        requirePositive(refId, "참조 ID");
    }

    private static void requirePositive(long value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + "는 1 이상이어야 합니다.");
        }
    }
}
