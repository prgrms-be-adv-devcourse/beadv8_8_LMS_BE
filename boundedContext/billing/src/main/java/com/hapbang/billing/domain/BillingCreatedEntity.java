package com.hapbang.billing.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreRemove;
import lombok.Getter;

// Billing 금융 엔티티의 ID·생성 시각을 제공하고 JPA를 통한 물리 삭제를 차단한다.
@Getter
@MappedSuperclass
public abstract class BillingCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    // 최초 저장 직전에 생성 시각을 현재 Instant의 마이크로초 정밀도로 기록한다.
    @PrePersist
    protected void recordCreation() {
        createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    // JPA가 금융 엔티티를 물리 삭제하려 하면 예외를 발생시켜 삭제를 차단한다.
    @PreRemove
    protected void rejectRemoval() {
        throw new UnsupportedOperationException("금융 데이터는 물리 삭제할 수 없습니다.");
    }
}
