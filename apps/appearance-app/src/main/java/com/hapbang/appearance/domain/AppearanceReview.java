package com.hapbang.appearance.domain;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import com.hapbang.shared.appearance.dto.AppearanceParticipantRole;
import com.hapbang.shared.appearance.event.AppearanceReviewPublishedEvent;
import com.hapbang.shared.jpa.BaseEntity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 출연 리뷰 (#512). 출연 확인이 끝난 출연에서 호스트·게스트가 상대에게 남긴 별점·본문 1건이다 (출연당 방향별 1건).
 * <ul>
 *     <li>쓸 수 있는지(당사자·확인된 출연·작성 기한)는 출연({@link Appearance#validateViewableBy},
 *     {@link Appearance#validateReviewable})이 확인하고,
 *     이 엔티티는 별점·본문 규칙과 공개 시각을 맡는다.</li>
 *     <li>수정·삭제는 없다. 상호 평가라 고쳐 쓰지 못하게 한다.</li>
 *     <li>블라인드 공개: 처음에는 {@code publishedAt}이 비어 있고, 상대 리뷰가 들어오면 두 리뷰를 함께 공개한다.</li>
 * </ul>
 */
@Entity
@Table(name = "reviews", schema = "appearances")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppearanceReview extends BaseEntity {

    /** 별점 범위 (DB CHECK와 같다) */
    public static final int RATING_MIN = 1;
    public static final int RATING_MAX = 5;

    /** 본문 최대 길이 (DB 컬럼 VARCHAR(1000)) */
    public static final int CONTENT_MAX = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long appearanceId;

    /** 쓴 사람 (그 출연의 호스트 또는 게스트) */
    private Long reviewerId;

    /** 받은 사람 (쓴 사람의 상대) */
    private Long revieweeId;

    /** 받은 사람이 그 출연에서 맡은 역할 */
    @Enumerated(EnumType.STRING)
    private AppearanceRole revieweeRole;

    /** 별점 1~5 */
    private int rating;

    /** 본문 (선택, 1,000자 이하). 공백뿐이면 비운다 */
    private String content;

    /** 공개 시각. 상대도 리뷰를 써서 함께 공개되기 전에는 null */
    private LocalDateTime publishedAt;

    /** 낙관적 락 */
    @Version
    private Long version;

    private AppearanceReview(Appearance appearance, Long reviewerId, int rating, String content) {
        this.appearanceId = appearance.getId();
        this.reviewerId = reviewerId;
        if (appearance.isHost(reviewerId)) {
            this.revieweeId = appearance.getGuestId();
            this.revieweeRole = AppearanceRole.GUEST;
        } else {
            this.revieweeId = appearance.getHostId();
            this.revieweeRole = AppearanceRole.HOST;
        }
        this.rating = rating;
        this.content = content;
    }

    /**
     * 리뷰를 쓴다 (공개 전). 출연 쪽 확인({@link Appearance#validateReviewable})은 호출한 쪽이 먼저 한다.
     *
     * @param rating  별점 (1~5, 필수)
     * @param content 본문 (선택, 앞뒤 공백을 지우고 1,000자 이하). 공백뿐이면 null로 저장한다
     * @throws AppearanceException 별점 없음·범위 밖, 본문 1,000자 초과({@code INVALID_REQUEST})
     * @throws IllegalStateException 출연의 호스트·게스트가 아닌 작성자 (호출한 쪽 확인 누락)
     */
    public static AppearanceReview write(Appearance appearance, Long reviewerId, Integer rating, String content) {
        if (!(appearance.isHost(reviewerId) || appearance.isGuest(reviewerId))) {
            throw new IllegalStateException("출연의 호스트·게스트만 리뷰를 쓸 수 있습니다: " + reviewerId);
        }
        int validRating = requireRating(rating);
        String trimmed = optionalContent(content);
        return new AppearanceReview(appearance, reviewerId, validRating, trimmed);
    }

    /**
     * 두 리뷰가 모두 들어와 공개한다. 이미 공개됐으면 처음 공개 시각을 그대로 둔다.
     * 시각은 DB 컬럼(TIMESTAMP(6))과 같은 마이크로초까지만 둔다. 공개 이벤트의 시각이 저장된 값과 같아야 하기 때문이다.
     */
    public void publish(LocalDateTime now) {
        if (publishedAt == null) {
            this.publishedAt = now.truncatedTo(ChronoUnit.MICROS);
        }
    }

    public boolean isPublished() {
        return publishedAt != null;
    }

    /**
     * 다른 모듈에 알리는 공개 이벤트 (#513). 본문은 담지 않는다.
     *
     * @throws IllegalStateException 아직 공개되지 않은 리뷰
     */
    public AppearanceReviewPublishedEvent toPublishedEvent() {
        if (!isPublished()) {
            throw new IllegalStateException("공개된 리뷰만 공개 이벤트로 알릴 수 있습니다: " + id);
        }
        return new AppearanceReviewPublishedEvent(id, appearanceId, reviewerId, revieweeId,
                AppearanceParticipantRole.valueOf(revieweeRole.name()), rating, publishedAt);
    }

    private static int requireRating(Integer rating) {
        if (rating == null || rating < RATING_MIN || rating > RATING_MAX) {
            throw new AppearanceException(AppearanceErrorCode.INVALID_REQUEST,
                    "별점은 %d~%d 사이의 정수여야 합니다.".formatted(RATING_MIN, RATING_MAX));
        }
        return rating;
    }

    private static String optionalContent(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String trimmed = content.strip();
        if (trimmed.length() > CONTENT_MAX) {
            throw new AppearanceException(AppearanceErrorCode.INVALID_REQUEST,
                    "리뷰 본문은 %,d자 이하여야 합니다.".formatted(CONTENT_MAX));
        }
        return trimmed;
    }
}
