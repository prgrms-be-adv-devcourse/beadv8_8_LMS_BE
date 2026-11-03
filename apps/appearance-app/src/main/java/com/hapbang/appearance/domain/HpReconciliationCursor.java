package com.hapbang.appearance.domain;

import java.time.LocalDateTime;

import com.hapbang.shared.jpa.BaseEntity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 지갑 보류 목록(변경된 보류 키셋) 커서 (#581). 참조 유형마다 하나다.
 * 끝까지 비교한 실행만 앞으로 옮긴다. 지갑의 {@code Instant} 커서를 KST {@link LocalDateTime}으로 저장한다.
 */
@Entity
@Table(name = "hp_reconciliation_cursors", schema = "appearances")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HpReconciliationCursor extends BaseEntity {

    @Id
    @Enumerated(EnumType.STRING)
    private HpTaskRefType referenceType;

    private LocalDateTime updatedAfter;

    private long afterId;

    @Version
    private Long version;

    public HpReconciliationCursor(HpTaskRefType referenceType, LocalDateTime updatedAfter, long afterId) {
        this.referenceType = referenceType;
        this.updatedAfter = updatedAfter;
        this.afterId = afterId;
    }

    public void moveTo(LocalDateTime updatedAfter, long afterId) {
        this.updatedAfter = updatedAfter;
        this.afterId = afterId;
    }
}
