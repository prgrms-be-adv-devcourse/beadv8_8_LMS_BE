package com.hapbang.appearance.app;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceRecruit;

import lombok.RequiredArgsConstructor;

/**
 * 출연 모집 조기 마감 (호스트). 모집글을 {@code CLOSED}로 바꾸고 대기 중 지원 정리·반환 작업 기록까지 한다.
 * <p>
 * 반환은 Billing 호출이라 커밋 뒤에 실행해야 해서, Facade가 아니라 여기서 트랜잭션을 연다 (요청서 거절과 같은 방식).
 */
@Service
@RequiredArgsConstructor
public class AppearanceRecruitCloseUseCase {

    private final AppearanceRecruitSupport appearanceRecruitSupport;
    private final AppearanceUserSupport appearanceUserSupport;
    private final AppearanceRecruitCloseRecorder appearanceRecruitCloseRecorder;
    @Qualifier(AppearanceConfig.CLOCK)
    private final Clock clock;

    /**
     * @return 기록한 반환 작업 ID (커밋 후 실행한다). 정원이 다 차서 돌려줄 금액이 없으면 null
     * @throws AppearanceException 호스트 자격, 없거나 볼 수 없는 모집글({@code APPEARANCE_RECRUIT_NOT_FOUND}),
     *                             호스트가 아님({@code NOT_RECRUIT_HOST}), 모집 중이 아니거나 마감 일시가 지남
     *                             ({@code APPEARANCE_RECRUIT_NOT_OPEN}), 동시 처리 충돌({@code CONCURRENT_UPDATE})
     */
    @Transactional
    public Long closeEarly(Long hostId, Long recruitId) {
        appearanceUserSupport.getParticipant(hostId);
        AppearanceRecruit recruit = appearanceRecruitSupport.getById(recruitId);
        recruit.validateVisible();

        LocalDateTime now = LocalDateTime.now(clock);
        recruit.closeEarly(hostId, now);
        return appearanceRecruitCloseRecorder.closeApplicationsAndRecordRelease(recruit, now);
    }
}
