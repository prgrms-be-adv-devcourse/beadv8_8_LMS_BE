package com.hapbang.lecture.domain;

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

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 서비스 간 HP 대사에서 어긋난 건 하나 (#580, D74). 고치지 않고 남기기만 한다. 만든 뒤 바꾸지 않는다.
 * 강의 상태·지갑 상태는 관리자가 읽는 요약 문자열이다(원본은 각 모듈의 기록).
 */
@Entity
@Table(name = "lecture_hp_reconciliation_mismatch", schema = "lectures")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LectureHpReconciliationMismatch {

    /** 상태 요약 저장 최대 길이 (DB 컬럼과 같다) */
    static final int STATE_MAX = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long runId;

    /** 지갑 참조 ID (주문 항목 ID) */
    @Column(nullable = false)
    private Long orderItemId;

    /** 주문 ID. 지갑에만 있는 보류는 강의에 항목이 없어 null */
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private LectureHpReconciliationRule rule;

    /** 비교할 때의 항목 상태. 지갑에만 있는 보류는 null */
    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private OrderItemStatus itemStatus;

    @Column(nullable = false, length = STATE_MAX)
    private String lectureState;

    @Column(nullable = false, length = STATE_MAX)
    private String walletState;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public LectureHpReconciliationMismatch(Long runId, Long orderItemId, Long orderId,
                                           LectureHpReconciliationRule rule, OrderItemStatus itemStatus,
                                           String lectureState, String walletState, LocalDateTime now) {
        this.runId = Objects.requireNonNull(runId, "대사 실행 ID는 필수입니다.");
        this.orderItemId = Objects.requireNonNull(orderItemId, "주문 항목 ID는 필수입니다.");
        this.orderId = orderId;
        this.rule = Objects.requireNonNull(rule, "규칙은 필수입니다.");
        this.itemStatus = itemStatus;
        this.lectureState = truncate(lectureState);
        this.walletState = truncate(walletState);
        this.createdAt = now;
    }

    private static String truncate(String state) {
        if (state.length() <= STATE_MAX) {
            return state;
        }
        return state.substring(0, STATE_MAX);
    }
}
