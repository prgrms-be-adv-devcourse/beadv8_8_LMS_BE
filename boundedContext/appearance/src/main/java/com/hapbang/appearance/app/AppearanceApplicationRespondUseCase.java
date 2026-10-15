package com.hapbang.appearance.app;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import com.hapbang.appearance.app.dto.AppearanceApplicationResDto;
import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.domain.Appearance;
import com.hapbang.appearance.domain.AppearanceApplication;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceRecruit;

import lombok.RequiredArgsConstructor;

/**
 * 출연 모집 지원 응답 (호스트): 수락·거절. 트랜잭션은 호출한 {@link AppearanceApplicationFacade}가 연다.
 * <ul>
 *     <li>수락: 확인 순서는 호스트 자격 → 호스트·모집 중·마감 전(모집글) → 대기 중인 지원 → 정원 → 게스트 자격.
 *     지원 {@code SELECTED} + 모집글 성립 인원 +1 + 출연 성립이 한 트랜잭션이다. HP는 이동하지 않는다(모집글 보류 유지).</li>
 *     <li>정원이 찼으면 수락만 막는다 (자동 마감 안 함, ISSUE.md APR-27 A2). 지원 뒤 정지·강등된 게스트는 수락할 수 없다(A5).</li>
 *     <li>같은 모집글에서 동시에 수락하면 모집글 버전(낙관적 락)이 맞지 않는 쪽을 {@code CONCURRENT_UPDATE}(409)로 막아 정원을 넘지 않는다.
 *     커밋 때 나면 공통 핸들러가 500으로 응답하므로 여기서 바로 반영(flush)해 업무 예외로 바꾼다.</li>
 *     <li>거절: 사유 없이 {@code NOT_SELECTED} (A4). 거절된 게스트는 다시 지원할 수 없다.</li>
 *     <li>다른 회원이 지원에 응답하려 하면 {@code NOT_RECRUIT_HOST}(403)다.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AppearanceApplicationRespondUseCase {

    private final AppearanceApplicationSupport appearanceApplicationSupport;
    private final AppearanceRecruitSupport appearanceRecruitSupport;
    private final AppearanceUserSupport appearanceUserSupport;
    private final AppearanceSupport appearanceSupport;
    @Qualifier(AppearanceConfig.CLOCK)
    private final Clock clock;

    /**
     * @throws AppearanceException 호스트 자격, 없는 지원, 호스트가 아님, 모집 중이 아님, 대기 중이 아님, 정원이 참({@code RECRUIT_FULL}),
     *                             게스트 자격, 동시 처리 충돌({@code CONCURRENT_UPDATE})
     */
    public AppearanceApplicationResDto accept(Long hostId, Long applicationId) {
        appearanceUserSupport.getParticipant(hostId);
        LocalDateTime now = LocalDateTime.now(clock);
        AppearanceApplication application = appearanceApplicationSupport.getById(applicationId);
        AppearanceRecruit recruit = appearanceRecruitSupport.getById(application.getRecruitId());
        recruit.validateRespondable(hostId, now);

        application.select(now);
        recruit.increaseSelected();
        appearanceUserSupport.getGuest(application.getGuestId());

        flushOrConflict();
        Appearance appearance = appearanceSupport.saveNow(Appearance.establishFrom(recruit, application, now));
        return AppearanceApplicationResDto.from(application, appearance.getId());
    }

    /**
     * @throws AppearanceException 호스트 자격, 없는 지원, 호스트가 아님, 모집 중이 아님, 대기 중이 아님, 동시 처리 충돌
     */
    public AppearanceApplicationResDto reject(Long hostId, Long applicationId) {
        appearanceUserSupport.getParticipant(hostId);
        LocalDateTime now = LocalDateTime.now(clock);
        AppearanceApplication application = appearanceApplicationSupport.getById(applicationId);
        AppearanceRecruit recruit = appearanceRecruitSupport.getById(application.getRecruitId());
        recruit.validateRespondable(hostId, now);

        application.reject(now);

        flushOrConflict();
        return AppearanceApplicationResDto.from(application, null);
    }

    /** 지원·모집글 변경을 바로 반영한다. 다른 요청이 먼저 바꿨으면(버전 충돌) 409 */
    private void flushOrConflict() {
        try {
            appearanceApplicationSupport.flush();
        } catch (ObjectOptimisticLockingFailureException exception) {
            throw new AppearanceException(AppearanceErrorCode.CONCURRENT_UPDATE);
        }
    }
}
