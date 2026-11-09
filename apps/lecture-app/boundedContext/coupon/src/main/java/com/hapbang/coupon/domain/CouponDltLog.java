package com.hapbang.coupon.domain;

import java.time.Duration;
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
import jakarta.persistence.Version;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 제한 재시도 뒤에도 처리하지 못해 DLT로 보낸 메시지의 격리 기록. 발급 요청({@link CouponDltKind#ISSUE})과 주문 결제·취소 완료
 * ({@link CouponDltKind#ORDER})를 함께 담고 {@code kind}로 가른다. 관리자가 원문으로 재처리하거나 영구 거절한다.
 * <p>
 * 상태 전이는 {@link CouponDltStatus}: 재발행은 전송 <b>전에</b> PENDING → REPLAYING으로 선점(선점 시각)하고, ACK를 받으면
 * {@link #completeReplay}로 REPLAYED, 전송이 실패하면 {@link #revertReplay}로 PENDING에 되돌린다. 선점한 프로세스가 죽어 REPLAYING이
 * 남으면 선점 lease가 지난 뒤({@link #canClaim}) 재발행·영구 거절이 다시 선점할 수 있다. 영구 거절을 다시 요청하면 상태를 바꾸지 않고
 * {@code false}를 돌려준다(보상만 다시 시도할 수 있게). 그 밖의 재전이는 {@link CouponDomainError#INVALID_STATUS_TRANSITION}이다.
 * 역직렬화에 실패한 메시지와 주문 이벤트 기록은 쿠폰 ID·요청 ID가 없다. 주문 이벤트의 재처리 선점·완료도 같은 상태 전이를 쓰고, 그때
 * REPLAYED는 원문으로 소비 UseCase를 다시 실행해 끝냈다는 뜻이다.
 */
@Entity
@Table(name = "coupon_dlt_log", schema = "coupon")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponDltLog {

    /** 실패 원인 칸 길이 */
    static final int MAX_FAILURE_CAUSE = 1000;

    /** 메시지 key 칸 길이 */
    static final int MAX_MESSAGE_KEY = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 기록을 남긴 소비자. 관리자 재처리·영구 거절이 이 값으로 갈린다 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CouponDltKind kind;

    private Long couponId;

    @Column(length = 100)
    private String issueRequestId;

    @Column(nullable = false, length = 200)
    private String sourceTopic;

    @Column(nullable = false)
    private int sourcePartition;

    @Column(nullable = false)
    private long sourceOffset;

    @Column(length = MAX_MESSAGE_KEY)
    private String messageKey;

    /** 원문(JSON, 역직렬화 실패면 받은 바이트) */
    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(nullable = false, length = MAX_FAILURE_CAUSE)
    private String failureCause;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CouponDltStatus status;

    @Column(length = 500)
    private String rejectReason;

    /** 재발행 선점 시각. REPLAYING이면 반드시 있고, 선점 lease를 이 시각부터 잰다. 전송 실패로 되돌리면 지운다 */
    private Instant replayClaimedAt;

    /** 재발행·영구 거절한 관리자 */
    private Long handledBy;

    private Instant handledAt;

    /** 영구 거절 뒤 Redis 수량 보상 결과. 보상 전에는 없다 */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private IssueCompensation compensationResult;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    /** 관리자 재발행·영구 거절이 동시에 같은 기록을 바꾸지 않게 한다 */
    @Version
    @Column(nullable = false)
    private long version;

    private CouponDltLog(CouponDltKind kind, Source source, Long couponId, String issueRequestId, String payload,
                         String failureCause, Instant now) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.couponId = couponId;
        this.issueRequestId = issueRequestId;
        this.sourceTopic = Objects.requireNonNull(source.topic(), "topic");
        this.sourcePartition = source.partition();
        this.sourceOffset = source.offset();
        // key·실패 원인은 받은 메시지·예외에서 온 값이라 NUL(text 컬럼에 저장 불가)을 바꾸고 칸 길이로 자른다. 저장이 실패하면 DLT로 보내지
        // 못해 그 partition 소비가 멈춘다. 원문은 호출자가 저장할 수 있는 값으로 넘긴다(CouponDltPayload.rawText)
        this.messageKey = source.key() == null ? null : fit(source.key(), MAX_MESSAGE_KEY);
        this.payload = Objects.requireNonNull(payload, "payload");
        this.failureCause = fit(failureCause == null || failureCause.isBlank() ? "원인 없음" : failureCause,
                MAX_FAILURE_CAUSE);
        this.status = CouponDltStatus.PENDING;
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    /**
     * 격리 기록(PENDING).
     *
     * @param couponId       읽은 메시지의 쿠폰 ID. 역직렬화 실패면 null
     * @param issueRequestId 읽은 메시지의 요청 ID. 역직렬화 실패면 null
     * @param failureCause   실패 원인. 칸 길이를 넘으면 자른다
     */
    public static CouponDltLog isolate(Source source, Long couponId, String issueRequestId, String payload,
                                       String failureCause, Instant now) {
        return new CouponDltLog(CouponDltKind.ISSUE, source, couponId, issueRequestId, payload, failureCause, now);
    }

    /**
     * 주문 결제·취소 완료 이벤트의 격리 기록(PENDING). 쿠폰 ID·요청 ID는 없다(주문 항목은 메시지 key에 있다).
     *
     * @param payload      원문(읽은 메시지는 이벤트 JSON, 역직렬화 실패면 받은 바이트)
     * @param failureCause 실패 원인. 칸 길이를 넘으면 자른다
     */
    public static CouponDltLog isolateOrderEvent(Source source, String payload, String failureCause, Instant now) {
        return new CouponDltLog(CouponDltKind.ORDER, source, null, null, payload, failureCause, now);
    }

    public boolean isPending() {
        return status == CouponDltStatus.PENDING;
    }

    /**
     * 재발행·영구 거절이 이 기록을 선점할 수 있는가: PENDING이거나, REPLAYING인데 선점 뒤 {@code replayLease}가 지났다(선점한 프로세스가
     * 전송 결과를 남기지 못하고 멈춘 것으로 본다).
     */
    public boolean canClaim(Instant now, Duration replayLease) {
        if (status == CouponDltStatus.PENDING) {
            return true;
        }
        return status == CouponDltStatus.REPLAYING && !now.isBefore(replayClaimedAt.plus(replayLease));
    }

    /**
     * 재발행 전송 전 선점: PENDING(또는 lease가 지난 REPLAYING) → REPLAYING. 선점 시각과 관리자를 남긴다.
     *
     * @param replayLease 선점 lease. 지난 REPLAYING만 다시 선점한다
     */
    public void claimReplay(long adminId, Instant now, Duration replayLease) {
        requireClaimable(now, replayLease, CouponDltStatus.REPLAYING);
        this.status = CouponDltStatus.REPLAYING;
        this.replayClaimedAt = now;
        handle(adminId, now);
    }

    /** 선점한 재발행이 broker ACK를 받았거나 같은 요청이 다시 격리돼 끝났다: REPLAYING → REPLAYED */
    public void completeReplay(Instant now) {
        transitTo(CouponDltStatus.REPLAYED);
        this.updatedAt = now;
    }

    /**
     * 선점한 재발행의 전송이 실패해 되돌린다: REPLAYING → PENDING. 처리한 관리자·시각·선점 시각을 지운다. 같은 요청의 새 PENDING이 이미
     * 있으면 호출자가 되돌리지 않는다(같은 요청의 PENDING은 하나).
     */
    public void revertReplay(Instant now) {
        transitTo(CouponDltStatus.PENDING);
        this.replayClaimedAt = null;
        this.handledBy = null;
        this.handledAt = null;
        this.updatedAt = now;
    }

    /**
     * 영구 거절: PENDING(또는 lease가 지난 REPLAYING) → REJECTED. 이미 REJECTED면 아무것도 바꾸지 않고 {@code false}다(보상 재시도 허용).
     *
     * @param replayLease 재발행 선점 lease. lease 안의 REPLAYING은 거절할 수 없다
     * @return 이번에 거절로 바꿨는가
     */
    public boolean reject(long adminId, String reason, Instant now, Duration replayLease) {
        if (reason == null || reason.isBlank()) {
            throw new CouponDomainException(CouponDomainError.REJECT_REASON_REQUIRED);
        }
        if (status == CouponDltStatus.REJECTED) {
            return false;
        }
        requireClaimable(now, replayLease, CouponDltStatus.REJECTED);
        this.status = CouponDltStatus.REJECTED;
        this.rejectReason = reason;
        handle(adminId, now);
        return true;
    }

    /** 영구 거절 뒤 보상 결과를 남긴다. 거절한 기록에만 */
    public void recordCompensation(IssueCompensation result, Instant now) {
        if (status != CouponDltStatus.REJECTED) {
            throw new CouponDomainException(CouponDomainError.INVALID_STATUS_TRANSITION,
                    "영구 거절한 DLT 기록에만 보상 결과를 남깁니다.");
        }
        this.compensationResult = Objects.requireNonNull(result, "result");
        this.updatedAt = now;
    }

    private void transitTo(CouponDltStatus next) {
        if (!status.canTransitTo(next)) {
            throw new CouponDomainException(CouponDomainError.INVALID_STATUS_TRANSITION,
                    "DLT 기록을 " + status + "에서 " + next + "(으)로 바꿀 수 없습니다.");
        }
        this.status = next;
    }

    private void requireClaimable(Instant now, Duration replayLease, CouponDltStatus next) {
        if (!canClaim(now, replayLease)) {
            throw new CouponDomainException(CouponDomainError.INVALID_STATUS_TRANSITION,
                    "DLT 기록을 " + status + "에서 " + next + "(으)로 바꿀 수 없습니다.");
        }
    }

    private void handle(long adminId, Instant now) {
        this.handledBy = adminId;
        this.handledAt = now;
        this.updatedAt = now;
    }

    /** NUL을 대체 문자(U+FFFD)로 바꾸고 {@code max}자로 자른다 */
    private static String fit(String value, int max) {
        String safe = value.replace('\0', '\uFFFD');
        return safe.length() <= max ? safe : safe.substring(0, max);
    }

    /** 실패한 메시지의 위치 */
    public record Source(String topic, int partition, long offset, String key) {
    }
}
