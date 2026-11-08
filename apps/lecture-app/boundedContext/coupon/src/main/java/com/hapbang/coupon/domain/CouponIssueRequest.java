package com.hapbang.coupon.domain;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 발급 요청의 DB 최종 결과. 발급 확정 소비자가 쓰고, 요청 상태 조회가 읽는다.
 * <p>
 * 발급 요청 ID는 쿠폰 안에서만 유일하다(UNIQUE(coupon_id, issue_request_id)). PK는 DB가 만드는 대리 키라 저장은 항상 새 행
 * INSERT이고, 같은 쿠폰·같은 요청 ID를 다시 저장하면 기존 결과를 덮지 않고 유일 제약 위반으로 실패한다(같은 요청 ID는 같은 결과).
 */
@Entity
@Table(name = "coupon_issue_request", schema = "coupon")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponIssueRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long couponId;

    @Column(nullable = false, length = 100)
    private String issueRequestId;

    @Column(nullable = false)
    private Long memberId;

    @Column(nullable = false)
    private long epoch;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CouponIssueResult result;

    @Column(length = 500)
    private String rejectReason;

    /** 최초 접수 시각 */
    @Column(nullable = false)
    private Instant acceptedAt;

    @Column(nullable = false)
    private Instant processedAt;

    /** 발급된 쿠폰. ISSUED만 */
    private Long memberCouponId;

    /** 거절 뒤 Redis 수량 보상 진행 상태. 값 목록은 보상을 구현할 때 정한다 */
    @Column(length = 30)
    private String compensationStatus;

    private CouponIssueRequest(String issueRequestId, Long memberId, Long couponId, long epoch,
                               CouponIssueResult result, String rejectReason, Instant acceptedAt,
                               Instant processedAt, Long memberCouponId) {
        this.issueRequestId = Objects.requireNonNull(issueRequestId, "issueRequestId");
        this.memberId = Objects.requireNonNull(memberId, "memberId");
        this.couponId = Objects.requireNonNull(couponId, "couponId");
        this.epoch = epoch;
        this.result = result;
        this.rejectReason = rejectReason;
        this.acceptedAt = Objects.requireNonNull(acceptedAt, "acceptedAt");
        this.processedAt = Objects.requireNonNull(processedAt, "processedAt");
        this.memberCouponId = memberCouponId;
    }

    /** 발급 확정 결과 */
    public static CouponIssueRequest issued(String issueRequestId, Long memberId, Long couponId, long epoch,
                                            Instant acceptedAt, Long memberCouponId, Instant processedAt) {
        return new CouponIssueRequest(issueRequestId, memberId, couponId, epoch, CouponIssueResult.ISSUED, null,
                acceptedAt, processedAt, Objects.requireNonNull(memberCouponId, "memberCouponId"));
    }

    /** 거절 확정 결과 */
    public static CouponIssueRequest rejected(String issueRequestId, Long memberId, Long couponId, long epoch,
                                              Instant acceptedAt, String rejectReason, Instant processedAt) {
        return new CouponIssueRequest(issueRequestId, memberId, couponId, epoch, CouponIssueResult.REJECTED,
                rejectReason, acceptedAt, processedAt, null);
    }

    /** 상태 판정 규칙의 입력({@link IssueStatusRule}) */
    public IssueStatusRule.DbRecord toDbRecord() {
        return new IssueStatusRule.DbRecord(memberId, result);
    }
}
