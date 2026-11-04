package com.hapbang.lecture.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 장바구니에 담은 강의 한 개. {@link Cart}를 통해서만 만들고 뺀다.
 */
@Entity
@Table(name = "cart_item", schema = "lectures")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CartItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cart_id", nullable = false)
    private Cart cart;

    // 다른 애그리거트라 ID로만 참조한다 (FK 없음)
    @Column(nullable = false)
    private Long lectureId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt;

    CartItem(Cart cart, Long lectureId, LocalDateTime now) {
        this.cart = cart;
        this.lectureId = lectureId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void markDeleted(LocalDateTime now) {
        this.deletedAt = now;
        this.updatedAt = now;
    }
}
