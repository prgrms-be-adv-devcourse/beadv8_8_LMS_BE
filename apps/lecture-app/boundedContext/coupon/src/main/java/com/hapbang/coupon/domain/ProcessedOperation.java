package com.hapbang.coupon.domain;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 처리한 작업(이벤트)의 멱등 기록. 업무 반영과 같은 트랜잭션에 넣고, 작업 ID는 UNIQUE다. 같은 작업 ID가 다시 오면 내용 식별값으로
 * 같은 요청의 재전달인지(같은 결과로 끝낸다), 같은 ID의 다른 내용인지(충돌) 가른다({@link #sameContent}).
 * <p>
 * 마지막 이벤트 하나만 기억하는 방식이 아니라 작업마다 한 행이다.
 */
@Entity
@Table(name = "processed_operation", schema = "coupon")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedOperation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String operationId;

    /** 같은 작업 ID의 다른 내용을 가려내는 요청 내용 식별값 */
    @Column(nullable = false, length = 500)
    private String contentKey;

    /** 처리 결과. 발급 요청은 ISSUED·REJECTED, 쿠폰 예약은 RESERVED·REJECTED, 쿠폰 해제는 RELEASED */
    @Column(nullable = false, length = 30)
    private String result;

    /** 결과의 세부 값. 쿠폰 예약 거절이면 거절 코드다. 발급 요청 기록은 비어 있다 */
    @Column(length = 100)
    private String detail;

    @Column(nullable = false)
    private Instant processedAt;

    private ProcessedOperation(String operationId, String contentKey, String result, String detail,
                               Instant processedAt) {
        this.operationId = Objects.requireNonNull(operationId, "operationId");
        this.contentKey = Objects.requireNonNull(contentKey, "contentKey");
        this.result = Objects.requireNonNull(result, "result");
        this.detail = detail;
        this.processedAt = Objects.requireNonNull(processedAt, "processedAt");
    }

    /** 발급 요청 처리 기록. 작업 ID·내용 식별값은 {@link IssueDelivery}가 정한다 */
    public static ProcessedOperation of(IssueDelivery delivery, CouponIssueResult result, Instant processedAt) {
        return new ProcessedOperation(delivery.operationId(), delivery.contentKey(), result.name(), null, processedAt);
    }

    /**
     * 쿠폰 사용 작업(예약·해제) 기록.
     *
     * @param detail 결과의 세부 값(예약 거절이면 거절 코드). 없으면 {@code null}
     */
    public static ProcessedOperation ofWork(String operationId, String contentKey, String result, String detail,
                                            Instant processedAt) {
        return new ProcessedOperation(operationId, contentKey, result, detail, processedAt);
    }

    /** 이 기록과 같은 내용 식별값인가 */
    public boolean sameContent(String contentKey) {
        return this.contentKey.equals(contentKey);
    }

    /** 이 기록과 같은 내용의 요청인가 */
    public boolean sameContent(IssueDelivery delivery) {
        return contentKey.equals(delivery.contentKey());
    }
}
