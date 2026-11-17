package com.hapbang.auxiliary.chatbot.domain;

import java.time.LocalDateTime;

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
 * 회원 그룹이 재시도를 다 쓰고 DLT로 보낸 회원 이벤트 (#976). 관리자가 이유를 보고 다시 처리한다.
 * <p>
 * 원문({@code payload})은 다시 처리할 때만 쓴다. 다시 처리할 수 없는 메시지는 처음부터, 처리된 기록은 처리할 때 비운다.
 * 실패 이유는 마지막 실패 것으로 덮어쓴다.
 */
@Entity
@Table(name = "member_event_failure", schema = "chatbot")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberEventFailure {

    /** 메시지 컬럼 길이(코드포인트) */
    public static final int MESSAGE_MAX_LENGTH = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MemberEventType eventType;

    private boolean replayable;

    private String payload;

    @Column(nullable = false)
    private String sourceTopic;

    private int sourcePartition;

    private long sourceOffset;

    @Column(nullable = false)
    private String exceptionClass;

    @Column(nullable = false)
    private String rootCauseClass;

    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MemberEventFailureStatus status;

    @Column(nullable = false)
    private LocalDateTime failedAt;

    private int retryCount;

    private LocalDateTime lastRetriedAt;

    private LocalDateTime resolvedAt;

    private Long resolvedBy;

    private MemberEventFailure(Source source, Long userId, MemberEventType eventType, String payload, Cause cause,
            LocalDateTime failedAt) {
        this.sourceTopic = source.topic();
        this.sourcePartition = source.partition();
        this.sourceOffset = source.offset();
        this.userId = userId;
        this.eventType = eventType;
        this.replayable = payload != null;
        this.payload = payload;
        this.status = MemberEventFailureStatus.PENDING;
        this.failedAt = failedAt;
        describe(cause);
    }

    /**
     * DLT로 보낸 메시지를 기록한다.
     *
     * @param payload 다시 처리할 이벤트 JSON. 다시 처리할 수 없으면 null
     */
    public static MemberEventFailure of(Source source, Long userId, MemberEventType eventType, String payload, Cause cause,
            LocalDateTime failedAt) {
        return new MemberEventFailure(source, userId, eventType, payload, cause, failedAt);
    }

    /** 관리자가 다시 처리해 반영했다. 원문은 지운다 */
    public void resolve(Long adminId, LocalDateTime now) {
        this.status = MemberEventFailureStatus.RESOLVED;
        this.resolvedAt = now;
        this.resolvedBy = adminId;
        this.lastRetriedAt = now;
        this.retryCount++;
        this.payload = null;
    }

    /** 관리자가 다시 처리했지만 또 실패했다. 이유를 마지막 실패로 바꾼다 */
    public void retryFailed(Cause cause, LocalDateTime now) {
        this.lastRetriedAt = now;
        this.retryCount++;
        describe(cause);
    }

    /** 회원이 마스킹돼 원문의 개인 정보를 비운 것으로 바꾼다 */
    public void replacePayload(String payload) {
        this.payload = payload;
    }

    private void describe(Cause cause) {
        this.exceptionClass = cause.exceptionClass();
        this.rootCauseClass = cause.rootCauseClass();
        this.message = cause.message() == null ? null : TextCut.head(cause.message(), MESSAGE_MAX_LENGTH);
    }

    /**
     * 원래 메시지의 위치
     *
     * @param topic     원본 토픽
     * @param partition 원본 파티션
     * @param offset    원본 offset
     */
    public record Source(String topic, int partition, long offset) {
    }

    /**
     * 실패 이유. 개인정보를 거른 뒤의 값이다
     *
     * @param exceptionClass 예외 클래스(리스너 감싸기를 벗긴 것)
     * @param rootCauseClass 가장 안쪽 원인 클래스
     * @param message        거른 메시지. 남기지 않으면 null
     */
    public record Cause(String exceptionClass, String rootCauseClass, String message) {
    }
}
