package com.hapbang.shared.lecture.event;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.springframework.modulith.events.Externalized;

/**
 * 쿠폰을 쓴 주문 항목의 구매 취소·반환이 끝났다(구매 취소 반환과 이의 제기 인정 반환 모두). 쿠폰 모듈은 이 이벤트로 쿠폰을 복원하거나 만료한다.
 * 쿠폰이 있는 항목만, 반환 완료를 기록하는 트랜잭션에서 한 번 발행한다. key는 {@code order-item:<주문 항목 ID>}라
 * 같은 항목의 결제 완료({@link OrderItemPaidEvent})와 순서가 지켜진다.
 * eventId는 항목마다 결정적({@value #EVENT_ID_PREFIX}{@code <주문 항목 ID>})이라 재발행해도 같은 값이고, 받는 쪽은 이 값으로 중복을 거른다.
 *
 * @param eventId                 이벤트 ID. {@link #eventIdOf(Long)}로 만든다
 * @param orderId                 주문 ID
 * @param orderItemId             주문 항목 ID
 * @param memberId                구매자 회원 ID
 * @param usages                  이 항목에 쓴 쿠폰의 사용 시도. 한 개 이상이다
 * @param billingReleaseRequestId 구매자 HP를 돌려준 지갑 작업의 요청 ID. 구매자 금액이 0 HP라 지갑을 부르지 않았으면 {@code null}
 * @param budgetRestorations      쿠폰별 플랫폼 예산 복구 결과. 플랫폼 부담이 0이면 빈 목록이다
 * @param completedAt             반환 완료 시각
 */
@Externalized(LectureEventTopic.NAME + "::#{'order-item:' + #this.orderItemId()}")
public record OrderItemCancelCompletedEvent(
        String eventId,
        Long orderId,
        Long orderItemId,
        Long memberId,
        List<Usage> usages,
        String billingReleaseRequestId,
        List<BudgetRestoration> budgetRestorations,
        Instant completedAt
) {

    /** {@code eventType} 헤더 값. 레코드 이름을 바꿔도 그대로 둔다(받는 쪽 계약) */
    public static final String EVENT_TYPE = "OrderItemCancelCompletedEvent";

    /** eventId 접두어. 뒤에 주문 항목 ID가 붙는다 */
    public static final String EVENT_ID_PREFIX = "order-item-cancel-completed:";

    public OrderItemCancelCompletedEvent {
        Objects.requireNonNull(eventId, "eventId는 필수입니다.");
        Objects.requireNonNull(orderId, "orderId는 필수입니다.");
        Objects.requireNonNull(orderItemId, "orderItemId는 필수입니다.");
        Objects.requireNonNull(memberId, "memberId는 필수입니다.");
        Objects.requireNonNull(usages, "usages는 필수입니다.");
        Objects.requireNonNull(budgetRestorations, "budgetRestorations는 필수입니다.");
        Objects.requireNonNull(completedAt, "completedAt은 필수입니다.");
        if (usages.isEmpty()) {
            throw new IllegalArgumentException("usages는 한 개 이상이어야 합니다.");
        }
        usages = List.copyOf(usages);
        budgetRestorations = List.copyOf(budgetRestorations);
    }

    /** eventId를 규칙대로 채워 만든다 */
    public static OrderItemCancelCompletedEvent of(Long orderId, Long orderItemId, Long memberId, List<Usage> usages,
                                                   String billingReleaseRequestId,
                                                   List<BudgetRestoration> budgetRestorations,
                                                   Instant completedAt) {
        return new OrderItemCancelCompletedEvent(eventIdOf(orderItemId), orderId, orderItemId, memberId, usages,
                billingReleaseRequestId, budgetRestorations, completedAt);
    }

    /** 주문 항목의 취소 완료 eventId: {@value #EVENT_ID_PREFIX}{@code <주문 항목 ID>} */
    public static String eventIdOf(Long orderItemId) {
        return EVENT_ID_PREFIX + Objects.requireNonNull(orderItemId, "orderItemId는 필수입니다.");
    }

    /**
     * 쿠폰 사용 시도 하나.
     *
     * @param memberCouponId 발급 쿠폰 ID
     * @param generation     예약으로 오른 generation(쿠폰 예약 응답의 reservedGeneration)
     */
    public record Usage(
            long memberCouponId,
            long generation
    ) {
    }

    /**
     * 쿠폰 한 장의 플랫폼 부담분을 그 쿠폰 예산 예약으로 되돌린 결과.
     *
     * @param couponId       쿠폰 정책 ID
     * @param memberCouponId 발급 쿠폰 ID
     * @param generation     예약으로 오른 generation
     * @param amount         예산으로 되돌린 플랫폼 부담분(HP)
     */
    public record BudgetRestoration(
            long couponId,
            long memberCouponId,
            long generation,
            long amount
    ) {
    }
}
