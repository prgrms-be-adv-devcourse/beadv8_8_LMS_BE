package com.hapbang.appearance.app;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.hapbang.appearance.app.dto.AppearanceApplicationResDto;
import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.domain.AppearanceApplication;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceRecruit;

import lombok.RequiredArgsConstructor;

/**
 * 출연 모집 지원 (게스트). 트랜잭션은 호출한 {@link AppearanceApplicationFacade}가 연다.
 * <p>
 * 확인 순서: 게스트 자격 → 모집글이 보이는지 → 지원할 수 있는지(자기 모집글 아님, 모집 중·마감 전, 메시지 길이) → 첫 지원인지.
 * 정원이 찼어도 지원은 받는다 (ISSUE.md APR-27 A2). 같은 게스트가 동시에 두 번 지원하면 DB 유니크 제약이 막는다.
 * <p>
 * 모집글은 공유 잠금으로 읽는다. 지원끼리는 막지 않고, 같은 모집글의 마감과는 순서가 정해져
 * 마감된 모집글에 대기 지원이 남지 않는다 (APR-28 C9).
 */
@Service
@RequiredArgsConstructor
public class AppearanceApplicationApplyUseCase {

    /** 게스트당 1회 지원 제한을 거는 DB 유니크 제약 이름 (마이그레이션과 같아야 한다) */
    static final String RECRUIT_GUEST_UNIQUE = "uk_appearance_applications_recruit_guest";

    private final AppearanceApplicationSupport appearanceApplicationSupport;
    private final AppearanceRecruitSupport appearanceRecruitSupport;
    private final AppearanceUserSupport appearanceUserSupport;
    @Qualifier(AppearanceConfig.CLOCK)
    private final Clock clock;

    /**
     * @throws AppearanceException 게스트 자격, 없거나 보이지 않는 모집글({@code APPEARANCE_RECRUIT_NOT_FOUND}),
     *                             자기 모집글, 모집 중이 아님, 이미 지원함({@code DUPLICATE_APPLICATION})
     */
    public AppearanceApplicationResDto apply(Long guestId, Long recruitId, String message) {
        appearanceUserSupport.getParticipant(guestId);
        AppearanceRecruit recruit = appearanceRecruitSupport.getForApply(recruitId);
        recruit.validateVisible();
        AppearanceApplication application = AppearanceApplication.apply(recruit, guestId, message,
                LocalDateTime.now(clock));

        if (appearanceApplicationSupport.existsByRecruitAndGuest(recruitId, guestId)) {
            throw new AppearanceException(AppearanceErrorCode.DUPLICATE_APPLICATION);
        }
        try {
            AppearanceApplication saved = appearanceApplicationSupport.saveNow(application);
            return AppearanceApplicationResDto.from(saved, null);
        } catch (DataIntegrityViolationException exception) {
            // 위 확인을 동시에 통과한 지원은 DB 유니크 제약에서 막힌다.
            if (isRecruitGuestConflict(exception)) {
                throw new AppearanceException(AppearanceErrorCode.DUPLICATE_APPLICATION);
            }
            throw exception;
        }
    }

    private boolean isRecruitGuestConflict(DataIntegrityViolationException exception) {
        String message = exception.getMostSpecificCause().getMessage();
        return message != null && message.contains(RECRUIT_GUEST_UNIQUE);
    }
}
