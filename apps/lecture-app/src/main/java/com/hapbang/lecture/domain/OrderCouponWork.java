package com.hapbang.lecture.domain;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주문 하나의 쿠폰 예약 또는 해제 요청. 주문마다 종류별로 한 행이다.
 * <ul>
 *     <li>업무 상태와 같은 강의 트랜잭션에서 만들고, 쿠폰 내부 API 호출은 커밋 뒤 트랜잭션 밖에서 한다.</li>
 *     <li>보내기 전마다 {@link #startAttempt}로 시도를 기록한다. 다음 시도 시각까지는 재시도 배치가 집어 가지 않는다.</li>
 *     <li>결과를 모르면 {@code PENDING}으로 남아 같은 작업 ID·같은 내용으로 다시 보낸다. 쿠폰 모듈이 작업 ID로 멱등 처리한다.</li>
 *     <li>끝난 상태로 한 번 바뀌면 다시 바뀌지 않는다. 같은 결과가 다시 오면 그대로 둔다.</li>
 *     <li>삭제하지 않는다.</li>
 * </ul>
 */
@Entity
@Table(name = "order_coupon_work", schema = "lectures",
        uniqueConstraints = @UniqueConstraint(name = "uk_order_coupon_work_order_kind",
                columnNames = {"order_id", "kind"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderCouponWork {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderCouponWorkKind kind;

    /** 쿠폰 모듈의 멱등 키 */
    @Column(nullable = false, unique = true, length = 100)
    private String workId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderCouponWorkStatus status;

    /** 예약 거절 때 쿠폰이 돌려준 코드 */
    @Column(length = 100)
    private String rejectCode;

    /** 보낸 횟수(주문 흐름의 첫 시도 포함) */
    @Column(nullable = false)
    private int attemptCount;

    @Column(nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    private OrderCouponWork(Long orderId, OrderCouponWorkKind kind, LocalDateTime now) {
        this.orderId = Objects.requireNonNull(orderId, "주문 ID는 필수입니다.");
        this.kind = kind;
        this.workId = kind.workIdOf(orderId);
        this.status = OrderCouponWorkStatus.PENDING;
        this.nextAttemptAt = Objects.requireNonNull(now, "시각은 필수입니다.");
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 주문한 쿠폰의 예약 작업. 작업 ID는 {@code lecture-order:<주문 ID>:reserve}다 */
    public static OrderCouponWork reserve(Long orderId, LocalDateTime now) {
        return new OrderCouponWork(orderId, OrderCouponWorkKind.RESERVE, now);
    }

    /** 예약한 쿠폰의 해제 작업. 작업 ID는 {@code lecture-order:<주문 ID>:release}다 */
    public static OrderCouponWork release(Long orderId, LocalDateTime now) {
        return new OrderCouponWork(orderId, OrderCouponWorkKind.RELEASE, now);
    }

    public boolean isPending() {
        return status == OrderCouponWorkStatus.PENDING;
    }

    /** 시도 상한 전이라 더 보낼 수 있는지 */
    public boolean hasAttemptsLeft(int maxAttempts) {
        return attemptCount < maxAttempts;
    }

    /**
     * 보내기 직전에 시도를 기록한다. 다음 시도 시각을 지금 + {@code retryAfter}로 미뤄, 그 사이 재시도 배치가 같은 작업을 집어 가지 않게 한다.
     * 결과를 모르면 그 시각 뒤에 배치가 다시 보낸다.
     */
    public void startAttempt(LocalDateTime now, Duration retryAfter) {
        requirePending();
        this.attemptCount++;
        this.nextAttemptAt = now.plus(retryAfter);
        this.updatedAt = now;
    }

    /** 예약 성공. 이미 예약 성공이면 그대로 둔다 */
    public void markReserved(LocalDateTime now) {
        finish(OrderCouponWorkKind.RESERVE, OrderCouponWorkStatus.RESERVED, now);
    }

    /** 쿠폰이 예약을 거절했다. 이미 거절로 끝났으면 그대로 둔다 */
    public void markRejected(String rejectCode, LocalDateTime now) {
        Objects.requireNonNull(rejectCode, "거절 코드는 필수입니다.");
        if (finish(OrderCouponWorkKind.RESERVE, OrderCouponWorkStatus.REJECTED, now)) {
            this.rejectCode = rejectCode;
        }
    }

    /** 해제 성공. 이미 해제 성공이면 그대로 둔다 */
    public void markReleased(LocalDateTime now) {
        finish(OrderCouponWorkKind.RELEASE, OrderCouponWorkStatus.RELEASED, now);
    }

    /** 시도 상한에 닿아 자동 처리를 멈춘다. 시간만으로 해제하지 않고 운영자가 확인한다 */
    public void markDead(LocalDateTime now) {
        requirePending();
        this.status = OrderCouponWorkStatus.DEAD;
        this.updatedAt = now;
    }

    /** @return 이번에 바꿨으면 true, 이미 같은 결과로 끝나 있었으면 false */
    private boolean finish(OrderCouponWorkKind expectedKind, OrderCouponWorkStatus result, LocalDateTime now) {
        if (kind != expectedKind) {
            throw new IllegalStateException("쿠폰 작업 종류가 " + expectedKind + "가 아닙니다. workId=" + workId
                    + ", kind=" + kind);
        }
        if (status == result) {
            return false;
        }
        requirePending();
        this.status = result;
        this.updatedAt = now;
        return true;
    }

    private void requirePending() {
        if (status != OrderCouponWorkStatus.PENDING) {
            throw new IllegalStateException("쿠폰 작업이 PENDING이 아닙니다. workId=" + workId + ", status=" + status);
        }
    }
}
