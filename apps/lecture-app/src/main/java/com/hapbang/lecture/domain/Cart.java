package com.hapbang.lecture.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.SQLRestriction;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원별 장바구니 (회원당 1개, 설계 4.5).
 * <ul>
 *     <li>판매 중인 강의만 담는다. 본인 강의와 이미 담은 강의는 담을 수 없다.</li>
 *     <li>강의는 ID로만 참조한다. 담은 뒤 판매 중지·삭제되면 조회 시 구매 불가로 표시한다.</li>
 *     <li>뺀 항목은 Soft Delete로 남긴다. 담지 않은 강의를 빼면 무시한다(D18).</li>
 * </ul>
 */
@Entity
@Table(name = "cart", schema = "lectures",
        uniqueConstraints = @UniqueConstraint(name = "uc_cart_user_id", columnNames = "user_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Cart {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    // 저장만 전파한다. 뺀 항목은 Soft Delete로 남긴다.
    @OneToMany(mappedBy = "cart", cascade = CascadeType.PERSIST)
    @SQLRestriction("deleted_at is null")
    @OrderBy("createdAt DESC, id DESC")
    private List<CartItem> items = new ArrayList<>();

    private Cart(Long userId, LocalDateTime now) {
        this.userId = userId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 운영에서는 동시 생성을 막으려고 CartRepository.insertIfAbsent로 만든다. */
    public static Cart create(Long userId, LocalDateTime now) {
        if (userId == null) {
            throw new LectureException(LectureErrorCode.INVALID_REQUEST);
        }
        return new Cart(userId, now);
    }

    public List<CartItem> getItems() {
        return List.copyOf(items);
    }

    /**
     * 본인 강의 → 판매 중 → 중복 순서로 검사한다. 판매 중이 아닌 강의는 상세 조회처럼 없는 강의로 본다.
     */
    public void add(Lecture lecture, LocalDateTime now) {
        if (lecture.getSellerUserId().equals(userId)) {
            throw new LectureException(LectureErrorCode.OWN_LECTURE_NOT_PURCHASABLE);
        }
        if (!lecture.isOnSale()) {
            throw new LectureException(LectureErrorCode.LECTURE_NOT_FOUND);
        }
        if (items.stream().anyMatch(item -> item.getLectureId().equals(lecture.getId()))) {
            throw new LectureException(LectureErrorCode.ALREADY_IN_CART);
        }
        items.add(new CartItem(this, lecture.getId(), now));
        this.updatedAt = now;
    }

    /** 담지 않은 강의면 아무것도 하지 않는다. */
    public void remove(Long lectureId, LocalDateTime now) {
        items.stream()
                .filter(item -> item.getLectureId().equals(lectureId))
                .findFirst()
                .ifPresent(item -> {
                    item.markDeleted(now);
                    items.remove(item);
                    this.updatedAt = now;
                });
    }
}
