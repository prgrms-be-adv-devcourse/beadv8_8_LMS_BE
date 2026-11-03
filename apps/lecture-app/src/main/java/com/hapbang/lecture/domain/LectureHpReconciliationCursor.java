package com.hapbang.lecture.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.hapbang.shared.billing.domain.BillingReferenceType;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 지갑 보류 목록(바뀐 보류 키셋) 커서 (#580, D74). 참조 유형마다 하나이고 강의는 {@code LECTURE_ORDER_ITEM} 하나다.
 * 끝까지 비교한 실행만 앞으로 옮긴다. 지갑의 {@code Instant} 커서를 KST {@link LocalDateTime}으로 저장한다.
 */
@Entity
@Table(name = "lecture_hp_reconciliation_cursor", schema = "lectures")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LectureHpReconciliationCursor {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private BillingReferenceType referenceType;

    @Column(nullable = false)
    private LocalDateTime updatedAfter;

    @Column(nullable = false)
    private long afterId;

    // 참조 유형을 직접 정하는 ID라, 버전이 null이면 새 행으로 본다(Spring Data isNew)
    @Version
    @Column(nullable = false)
    private Long version;

    public LectureHpReconciliationCursor(BillingReferenceType referenceType, LocalDateTime updatedAfter,
                                         long afterId) {
        this.referenceType = referenceType;
        this.updatedAfter = updatedAfter;
        this.afterId = afterId;
    }

    public void moveTo(LocalDateTime updatedAfter, long afterId) {
        this.updatedAfter = updatedAfter;
        this.afterId = afterId;
    }
}
