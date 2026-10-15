package com.hapbang.appearance.app;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.hapbang.appearance.app.dto.AppearanceApplicationResDto;
import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.domain.Appearance;
import com.hapbang.appearance.domain.AppearanceApplication;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceRecruit;

import lombok.RequiredArgsConstructor;

/**
 * 출연 모집 지원 응답 (호스트): 수락·거절. 트랜잭션은 호출한 {@link AppearanceApplicationFacade}가 연다.
 * <ul>
 *     <li>수락: 확인 순서는 호스트 자격 → 호스트·모집 중·마감 전(모집글) → 대기 중인 지원 → 정원 → 게스트 자격.
 *     지원 {@code SELECTED} + 모집글 성립 인원 +1 + 출연 성립이 한 트랜잭션이다. HP는 이동하지 않는다(모집글 보류 유지).</li>
 *     <li>정원이 찼으면 수락만 막는다 (자동 마감 안 함, ISSUE.md APR-27 A2). 지원 뒤 정지·강등된 게스트는 수락할 수 없다(A5).</li>
 *     <li>수락은 모집글을 배타 잠금으로 읽는다(APR-28 C9). 같은 모집글의 수락·마감은 차례로 처리돼, 나중 수락은 최신 성립 인원을 보고
 *     정원이 찼으면 {@code RECRUIT_FULL}, 마감됐으면 {@code APPEARANCE_RECRUIT_NOT_OPEN}이 된다. 마감과 잠그는 순서(모집글 → 지원)가 같아
 *     교착 상태가 생기지 않는다.</li>
 *     <li>그래도 버전(낙관적 락)이 맞지 않으면(예: 지원 취소와 겹침) {@code CONCURRENT_UPDATE}(409). 커밋 때 나면 공통 핸들러가
 *     500으로 응답하므로 여기서 바로 반영(flush)해 업무 예외로 바꾼다.</li>
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
        // 모집글을 먼저 잠근다. 마감과 잠그는 순서(모집글 → 지원)를 맞춰 교착 상태를 막는다 (ISSUE.md APR-28 C9)
        AppearanceRecruit recruit = appearanceRecruitSupport.getForUpdate(application.getRecruitId());
        recruit.validateRespondable(hostId, now);

        application.select(now);
        recruit.increaseSelected();
        appearanceUserSupport.getGuest(application.getGuestId());

        appearanceApplicationSupport.flushOrConflict();
        Appearance appearance = appearanceSupport.saveNow(Appearance.establishFrom(recruit, application, now));
        return AppearanceApplicationResDto.from(application, recruit, appearance.getId());
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

        appearanceApplicationSupport.flushOrConflict();
        return AppearanceApplicationResDto.from(application, recruit, null);
    }
}
