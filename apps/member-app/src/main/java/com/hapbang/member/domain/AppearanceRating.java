package com.hapbang.member.domain;

import com.hapbang.shared.jpa.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원별 출연 평점 합계 (#555). 출연 앱의 리뷰 공개 이벤트로 쌓는다. 받은 회원이 그 출연에서 맡은 역할(호스트·게스트)로 나눠
 * 별점 합계와 개수만 갖고, 평균은 읽을 때 계산한다. 합계와 개수만 있으면 이벤트가 어떤 순서로 와도 같은 값이 된다.
 * <p>
 * 쓰기는 동시에 와도 값이 맞도록 저장소의 원자적 증가 쿼리로만 한다
 * ({@code AppearanceRatingRepository.add}). 이 엔티티는 읽기용이다
 */
@Entity
@Table(name = "appearance_rating", schema = "members")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppearanceRating extends BaseEntity {

    /** 리뷰를 받은 회원 ID */
    @Id
    private Long userId;

    @Column(nullable = false)
    private long hostRatingSum;

    @Column(nullable = false)
    private long hostRatingCount;

    @Column(nullable = false)
    private long guestRatingSum;

    @Column(nullable = false)
    private long guestRatingCount;

    /** 전체·호스트·게스트 평점 */
    public AppearanceRatingSummary summary() {
        return new AppearanceRatingSummary(
                RatingSummary.of(hostRatingSum + guestRatingSum, hostRatingCount + guestRatingCount),
                RatingSummary.of(hostRatingSum, hostRatingCount),
                RatingSummary.of(guestRatingSum, guestRatingCount));
    }
}
