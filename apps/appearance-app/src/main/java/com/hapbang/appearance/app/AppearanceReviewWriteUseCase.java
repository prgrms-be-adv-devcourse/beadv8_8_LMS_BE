package com.hapbang.appearance.app;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.domain.Appearance;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceReview;
import com.hapbang.shared.appearance.event.AppearanceReviewPublishedEvent;

import lombok.RequiredArgsConstructor;

/**
 * 출연 리뷰 작성 (#512). 트랜잭션은 호출한 {@link AppearanceReviewFacade}가 연다.
 * <ul>
 *     <li>쓸 수 있는지는 {@link AppearanceReviewEligibility}가 확인한다 (출연 상세의 {@code reviewable}과 같은 기준, #514).</li>
 *     <li>블라인드 공개: 상대 리뷰가 이미 있으면 같은 트랜잭션에서 두 리뷰의 공개 시각을 함께 채우고, 리뷰마다 공개 이벤트
 *     ({@link AppearanceReviewPublishedEvent})를 발행한다 (#513). 한쪽만 쓴 동안에는 발행하지 않는다.
 *     이벤트는 발행 기록에 남고 커밋 뒤 Kafka로 나간다(롤백되면 나가지 않음).</li>
 *     <li>출연 행을 배타 잠금({@code FOR UPDATE})으로 읽는다. 호스트·게스트가 동시에 써도 차례로 처리돼 나중 작성이
 *     먼저 쓴 리뷰를 보고 둘 다 공개한다. 같은 회원이 동시에 쓰면 나중 것은 {@code ALREADY_REVIEWED}이고,
 *     DB 유니크 제약이 최종으로 막는다.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AppearanceReviewWriteUseCase {

    /** 출연당 작성자 1명에 리뷰 하나를 거는 DB 유니크 제약 이름 (마이그레이션과 같아야 한다) */
    static final String APPEARANCE_REVIEWER_UNIQUE = "uk_appearance_reviews_appearance_reviewer";

    private final AppearanceSupport appearanceSupport;
    private final AppearanceReviewSupport appearanceReviewSupport;
    private final AppearanceReviewEligibility appearanceReviewEligibility;
    private final ApplicationEventPublisher eventPublisher;
    @Qualifier(AppearanceConfig.CLOCK)
    private final Clock clock;

    /**
     * @param rating  별점 (1~5, 필수)
     * @param content 본문 (선택, 1,000자 이하)
     * @return 저장한 리뷰 ID
     * @throws AppearanceException 없거나 당사자가 아닌 출연({@code APPEARANCE_NOT_FOUND}),
     *                             작성자 출연 회원 정보 없음({@code APPEARANCE_USER_NOT_FOUND})·정지·탈퇴({@code APPEARANCE_USER_NOT_ACTIVE})·일반 유저로 강등({@code NOT_INFLUENCER}), 확인된 출연 아님({@code APPEARANCE_NOT_REVIEWABLE}),
     *                             작성 기한 지남({@code REVIEW_PERIOD_ENDED}), 상대 정지·탈퇴({@code REVIEWEE_NOT_ACTIVE}),
     *                             이미 씀({@code ALREADY_REVIEWED}), 별점·본문 형식({@code INVALID_REQUEST})
     */
    public Long write(Long reviewerId, Long appearanceId, Integer rating, String content) {
        LocalDateTime now = LocalDateTime.now(clock);
        Appearance appearance = appearanceSupport.getForUpdate(appearanceId);
        appearanceReviewEligibility.validate(reviewerId, appearance, now);
        Long revieweeId = appearance.counterpartOf(reviewerId);

        AppearanceReview review = AppearanceReview.write(appearance, reviewerId, rating, content);
        AppearanceReview saved = saveOrAlreadyReviewed(review);
        publishIfBothWritten(saved, appearanceId, revieweeId, now);
        return saved.getId();
    }

    private AppearanceReview saveOrAlreadyReviewed(AppearanceReview review) {
        try {
            return appearanceReviewSupport.saveNow(review);
        } catch (DataIntegrityViolationException exception) {
            // 위 확인을 동시에 통과한 같은 회원의 리뷰는 DB 유니크 제약에서 막힌다 (출연 잠금이 빠졌을 때의 최종 방어)
            if (isAppearanceReviewerConflict(exception)) {
                throw new AppearanceException(AppearanceErrorCode.ALREADY_REVIEWED);
            }
            throw exception;
        }
    }

    /** 상대 리뷰가 이미 있으면 두 리뷰를 함께 공개하고 공개 이벤트를 2개 발행한다 (블라인드) */
    private void publishIfBothWritten(AppearanceReview written, Long appearanceId, Long revieweeId,
                                      LocalDateTime now) {
        Optional<AppearanceReview> counterpartReview = appearanceReviewSupport.findByAppearanceAndReviewer(
                appearanceId, revieweeId);
        if (counterpartReview.isEmpty()) {
            return;
        }
        AppearanceReview counterpart = counterpartReview.get();
        boolean counterpartAlreadyPublished = counterpart.isPublished();
        written.publish(now);
        counterpart.publish(now);
        appearanceReviewSupport.flush();
        // 회원 모듈이 평점을 계산하도록 이번에 공개된 리뷰마다 알린다 (커밋 뒤 Kafka, #513).
        // 상대 리뷰가 이미 공개돼 있으면(기한 만료 공개 배치, #515) 그 이벤트는 이미 나갔으므로 다시 보내지 않는다
        if (!counterpartAlreadyPublished) {
            eventPublisher.publishEvent(counterpart.toPublishedEvent());
        }
        eventPublisher.publishEvent(written.toPublishedEvent());
    }

    private boolean isAppearanceReviewerConflict(DataIntegrityViolationException exception) {
        String message = exception.getMostSpecificCause().getMessage();
        return message != null && message.contains(APPEARANCE_REVIEWER_UNIQUE);
    }
}
