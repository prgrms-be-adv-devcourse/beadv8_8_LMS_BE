package com.hapbang.appearance.app;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import com.hapbang.appearance.app.dto.AppearanceApplicationResDto;
import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.domain.AppearanceApplication;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;

import lombok.RequiredArgsConstructor;

/**
 * 출연 모집 지원 취소 (게스트). 호스트가 응답하기 전까지만 할 수 있고, 취소한 뒤에는 다시 지원할 수 없다.
 * 트랜잭션은 호출한 {@link AppearanceApplicationFacade}가 연다.
 * <p>
 * 호스트 수락과 동시에 취소하면 버전(낙관적 락)이 맞지 않는 쪽을 {@code CONCURRENT_UPDATE}(409)로 막는다.
 */
@Service
@RequiredArgsConstructor
public class AppearanceApplicationWithdrawUseCase {

    private final AppearanceApplicationSupport appearanceApplicationSupport;
    private final AppearanceUserSupport appearanceUserSupport;
    @Qualifier(AppearanceConfig.CLOCK)
    private final Clock clock;

    /**
     * @throws AppearanceException 게스트 자격, 없는 지원({@code APPLICATION_NOT_FOUND}), 지원한 게스트가 아님,
     *                             대기 중이 아님({@code APPLICATION_NOT_PENDING}), 동시 처리 충돌({@code CONCURRENT_UPDATE})
     */
    public AppearanceApplicationResDto withdraw(Long guestId, Long applicationId) {
        appearanceUserSupport.getParticipant(guestId);
        AppearanceApplication application = appearanceApplicationSupport.getById(applicationId);
        application.withdraw(guestId, LocalDateTime.now(clock));
        try {
            appearanceApplicationSupport.flush();
        } catch (ObjectOptimisticLockingFailureException exception) {
            throw new AppearanceException(AppearanceErrorCode.CONCURRENT_UPDATE);
        }
        return AppearanceApplicationResDto.from(application, null);
    }
}
