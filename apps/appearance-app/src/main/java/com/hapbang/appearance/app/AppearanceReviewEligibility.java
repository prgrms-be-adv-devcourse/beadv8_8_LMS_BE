package com.hapbang.appearance.app;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.hapbang.appearance.domain.Appearance;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceReviewPolicy;

import lombok.RequiredArgsConstructor;

/**
 * 회원이 지금 이 출연에 리뷰를 쓸 수 있는지 (#512, #514). 작성 API({@link AppearanceReviewWriteUseCase})와
 * 출연 상세의 {@code reviewable}이 같은 기준을 쓰도록 확인을 여기 한 곳에 둔다. 트랜잭션은 호출한 쪽이 연다.
 * <p>
 * 확인 순서: 출연이 있고 그 출연의 호스트·게스트인지(아니면 출연이 있다는 사실도 알리지 않고 404) → 작성자 자격(정상 상태, 인플루언서 이상)(403)
 * → 출연 확인이 끝났는지(409) → 작성 기한 안인지(409) → 상대가 정상 회원인지(409) → 처음 쓰는지(409).
 * <ul>
 *     <li>작성자는 출연 거래와 같은 자격(정상 상태, 인플루언서 이상)이어야 한다. 출연은 인플루언서끼리만 해서 일반 유저는 쓸 수 없고,
 *     로그인 뒤 정지·강등된 경우를 복제한 회원 정보로 다시 막는다. 상대는 정상 상태만 본다(받는 쪽 역할은 따지지 않음).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AppearanceReviewEligibility {

    private final AppearanceReviewSupport appearanceReviewSupport;
    private final AppearanceUserSupport appearanceUserSupport;
    private final AppearanceReviewPolicy appearanceReviewPolicy;

    /**
     * 쓸 수 없으면 그 이유로 예외를 던진다 (작성 API).
     *
     * @throws AppearanceException 당사자가 아닌 출연({@code APPEARANCE_NOT_FOUND}),
     *                             작성자 출연 회원 정보 없음({@code APPEARANCE_USER_NOT_FOUND})·정지·탈퇴({@code APPEARANCE_USER_NOT_ACTIVE})·일반 유저로 강등({@code NOT_INFLUENCER}),
     *                             확인된 출연 아님({@code APPEARANCE_NOT_REVIEWABLE}), 작성 기한 지남({@code REVIEW_PERIOD_ENDED}),
     *                             상대 정지·탈퇴({@code REVIEWEE_NOT_ACTIVE}), 이미 씀({@code ALREADY_REVIEWED})
     */
    public void validate(Long reviewerId, Appearance appearance, LocalDateTime now) {
        appearance.validateViewableBy(reviewerId);
        appearanceUserSupport.getParticipant(reviewerId);
        appearance.validateReviewable(appearanceReviewPolicy, now);

        Long revieweeId = appearance.counterpartOf(reviewerId);
        if (!appearanceUserSupport.isActive(revieweeId)) {
            throw new AppearanceException(AppearanceErrorCode.REVIEWEE_NOT_ACTIVE);
        }
        if (appearanceReviewSupport.existsByAppearanceAndReviewer(appearance.getId(), reviewerId)) {
            throw new AppearanceException(AppearanceErrorCode.ALREADY_REVIEWED);
        }
    }

    /** 지금 쓸 수 있는지 (출연 상세의 {@code reviewable}). 기준은 {@link #validate}와 같다 */
    public boolean canWrite(Long reviewerId, Appearance appearance, LocalDateTime now) {
        try {
            validate(reviewerId, appearance, now);
            return true;
        } catch (AppearanceException notWritable) {
            return false;
        }
    }
}
