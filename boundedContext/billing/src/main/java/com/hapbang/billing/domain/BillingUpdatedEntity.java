package com.hapbang.billing.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import lombok.Getter;

// 변경 가능한 Billing 금융 엔티티에 생성·수정 시점의 Instant 감사 시각을 기록한다.
@Getter
@MappedSuperclass
public abstract class BillingUpdatedEntity extends BillingCreatedEntity {

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    private Instant updatedAt;

    // 최초 저장 시 수정 시각을 생성 시각과 동일하게 기록한다.
    @PrePersist
    protected void recordInitialUpdate() {
        updatedAt = getCreatedAt();
    }

    // 엔티티 변경 사항을 DB에 반영하기 전에 수정 시각을 현재 Instant의 마이크로초 정밀도로 갱신한다.
    @PreUpdate
    protected void recordUpdate() {
        updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
