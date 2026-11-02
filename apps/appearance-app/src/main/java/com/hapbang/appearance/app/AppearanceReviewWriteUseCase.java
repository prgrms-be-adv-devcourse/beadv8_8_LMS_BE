package com.hapbang.appearance.app;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.domain.Appearance;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceReview;
import com.hapbang.appearance.domain.AppearanceReviewPolicy;

import lombok.RequiredArgsConstructor;

/**
 * 출연 리뷰 작성 (#512). 트랜잭션은 호출한 {@link AppearanceReviewFacade}가 연다.
 * <p>
 * 확인 순서: 출연이 있는지(404) → 그 출연의 호스트·게스트인지(403) → 작성자 자격(정상 상태, 인플루언서 이상)(403) → 출연 확인이 끝났는지(409)
 * → 작성 기한 안인지(409) → 상대가 정상 회원인지(409) → 처음 쓰는지(409).
 * <ul>
 *     <li>작성자는 출연 거래와 같은 자격(정상 상태, 인플루언서 이상)이어야 한다. 출연은 인플루언서끼리만 해서 일반 유저는 쓸 수 없고,
 *     로그인 뒤 정지·강등된 경우를 복제한 회원 정보로 다시 막는다. 상대는 정상 상태만 본다(받는 쪽 역할은 따지지 않음).</li>
 *     <li>블라인드 공개: 상대 리뷰가 이미 있으면 같은 트랜잭션에서 두 리뷰의 공개 시각을 함께 채운다.</li>
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
    private final AppearanceUserSupport appearanceUserSupport;
    private final AppearanceReviewPolicy appearanceReviewPolicy;
    @Qualifier(AppearanceConfig.CLOCK)
    private final Clock clock;

    /**
     * @param rating  별점 (1~5, 필수)
     * @param content 본문 (선택, 1,000자 이하)
     * @return 저장한 리뷰 ID
     * @throws AppearanceException 없는 출연({@code APPEARANCE_NOT_FOUND}), 당사자 아님({@code NOT_APPEARANCE_PARTICIPANT}),
     *                             작성자 정지·탈퇴({@code APPEARANCE_USER_NOT_ACTIVE})·일반 유저로 강등({@code NOT_INFLUENCER}), 확인된 출연 아님({@code APPEARANCE_NOT_REVIEWABLE}),
     *                             작성 기한 지남({@code REVIEW_PERIOD_ENDED}), 상대 정지·탈퇴({@code REVIEWEE_NOT_ACTIVE}),
     *                             이미 씀({@code ALREADY_REVIEWED}), 별점·본문 형식({@code INVALID_REQUEST})
     */
    public Long write(Long reviewerId, Long appearanceId, Integer rating, String content) {
        LocalDateTime now = LocalDateTime.now(clock);
        Appearance appearance = appearanceSupport.getForUpdate(appearanceId);
        appearance.validateReviewer(reviewerId);
        appearanceUserSupport.getParticipant(reviewerId);
        appearance.validateReviewable(appearanceReviewPolicy, now);

        Long revieweeId = appearance.counterpartOf(reviewerId);
        if (!appearanceUserSupport.isActive(revieweeId)) {
            throw new AppearanceException(AppearanceErrorCode.REVIEWEE_NOT_ACTIVE);
        }
        if (appearanceReviewSupport.existsByAppearanceAndReviewer(appearanceId, reviewerId)) {
            throw new AppearanceException(AppearanceErrorCode.ALREADY_REVIEWED);
        }

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

    /** 상대 리뷰가 이미 있으면 두 리뷰를 함께 공개한다 (블라인드) */
    private void publishIfBothWritten(AppearanceReview written, Long appearanceId, Long revieweeId,
                                      LocalDateTime now) {
        Optional<AppearanceReview> counterpartReview = appearanceReviewSupport.findByAppearanceAndReviewer(
                appearanceId, revieweeId);
        if (counterpartReview.isEmpty()) {
            return;
        }
        written.publish(now);
        counterpartReview.get().publish(now);
        appearanceReviewSupport.flush();
    }

    private boolean isAppearanceReviewerConflict(DataIntegrityViolationException exception) {
        String message = exception.getMostSpecificCause().getMessage();
        return message != null && message.contains(APPEARANCE_REVIEWER_UNIQUE);
    }
}
