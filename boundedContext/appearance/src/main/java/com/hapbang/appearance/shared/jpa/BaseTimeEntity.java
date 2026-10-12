package com.hapbang.appearance.shared.jpa;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import lombok.Getter;

import com.hapbang.appearance.shared.time.AppearanceTime;

/**
 * 출연 모듈 엔티티의 생성·수정 시각.
 * <p>
 * JPA Auditing({@code @EnableJpaAuditing})은 앱 전체에 적용되는 설정이라 쓰지 않고, 엔티티 콜백으로 채운다.
 * 기록용 시각이라 테스트에서 고정할 필요가 없으므로 Clock 빈 대신 {@link AppearanceTime#ZONE} 기준 현재 시각을 쓴다.
 * 기한·마감처럼 로직에 쓰는 시각은 반드시 Clock 빈으로 계산한다.
 */
@Getter
@MappedSuperclass
public abstract class BaseTimeEntity {

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now(AppearanceTime.ZONE);
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now(AppearanceTime.ZONE);
    }
}
