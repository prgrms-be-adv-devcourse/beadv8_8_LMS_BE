package com.hapbang.member.domain;

import java.time.LocalDateTime;

import com.hapbang.shared.appearance.dto.AppearanceParticipantRole;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 출연 평점에 반영한 리뷰 (#555). 리뷰 ID가 PK라 같은 리뷰를 한 번만 반영한다(최소 한 번 전달이라 같은 이벤트가 다시 온다).
 * 이벤트 값을 모두 남겨 평점 합계가 어긋나면 이 기록으로 다시 계산할 수 있다.
 * <p>
 * 쓰기는 중복을 오류 없이 거르도록 저장소의 {@code insert … on conflict do nothing}으로만 한다
 * ({@code AppearanceReviewReceiptRepository.insertIfAbsent}). 이 엔티티는 테이블 모양 확인·읽기용이다
 */
@Entity
@Table(name = "appearance_review_receipt", schema = "members")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppearanceReviewReceipt {

    /** 출연 앱의 리뷰 ID */
    @Id
    private Long reviewId;

    @Column(nullable = false)
    private Long appearanceId;

    /** 리뷰를 쓴 회원 ID */
    @Column(nullable = false)
    private Long reviewerId;

    /** 리뷰를 받은 회원 ID */
    @Column(nullable = false)
    private Long revieweeId;

    /** 받은 회원이 그 출연에서 맡은 역할 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AppearanceParticipantRole revieweeRole;

    /** 별점 1~5 */
    @Column(nullable = false)
    private int rating;

    /** 출연 앱의 공개 시각 (KST) */
    @Column(nullable = false)
    private LocalDateTime publishedAt;

    /** 반영 시각 */
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
