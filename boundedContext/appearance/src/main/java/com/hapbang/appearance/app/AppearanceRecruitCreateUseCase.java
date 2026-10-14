package com.hapbang.appearance.app;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.domain.AppearancePolicy;
import com.hapbang.appearance.domain.AppearanceRecruit;
import com.hapbang.appearance.domain.AppearanceRecruitContent;

import lombok.RequiredArgsConstructor;

/**
 * 출연 모집글 저장 (올리기의 첫 단계). 자격·값을 확인하고 {@code HOLD_PENDING}으로 저장한다.
 * <p>
 * 올리기는 "저장 → HP 보류 → 상태 변경"을 각각 따로 커밋해야 해서 Facade가 트랜잭션을 열지 않는다.
 * 그래서 이 단계는 여기서 직접 트랜잭션을 연다. 같은 호스트의 모집글 중복은 막지 않는다.
 */
@Service
@RequiredArgsConstructor
public class AppearanceRecruitCreateUseCase {

    private final AppearanceRecruitSupport appearanceRecruitSupport;
    private final AppearanceUserSupport appearanceUserSupport;
    private final AppearancePolicy appearancePolicy;
    @Qualifier(AppearanceConfig.CLOCK)
    private final Clock clock;

    /**
     * @throws com.hapbang.appearance.domain.AppearanceException 호스트 자격·입력 값 오류
     */
    @Transactional
    public AppearanceRecruit createPending(Long hostId, AppearanceRecruitContent content) {
        appearanceUserSupport.getParticipant(hostId);
        LocalDateTime now = LocalDateTime.now(clock);
        AppearanceRecruit recruit = AppearanceRecruit.create(hostId, content, appearancePolicy, now);
        return appearanceRecruitSupport.saveNow(recruit);
    }
}
