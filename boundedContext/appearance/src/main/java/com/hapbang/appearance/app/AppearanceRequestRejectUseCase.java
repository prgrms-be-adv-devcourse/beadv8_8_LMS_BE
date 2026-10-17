package com.hapbang.appearance.app;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.domain.AppearanceRequest;
import com.hapbang.appearance.domain.HpTaskReason;

import lombok.RequiredArgsConstructor;

/**
 * 출연 요청 거절 (게스트). 요청서를 {@code REJECTED}로 바꾸고 보류 HP 반환 작업을 기록한다.
 * <p>
 * 반환은 Billing 호출이라 커밋 뒤에 실행해야 해서, Facade가 아니라 여기서 트랜잭션을 연다 (요청서 보내기와 같은 방식).
 */
@Service
@RequiredArgsConstructor
public class AppearanceRequestRejectUseCase {

    private final AppearanceRequestSupport appearanceRequestSupport;
    private final AppearanceUserSupport appearanceUserSupport;
    private final AppearanceRequestReleaseRecorder appearanceRequestReleaseRecorder;
    @Qualifier(AppearanceConfig.CLOCK)
    private final Clock clock;

    /**
     * @param reason 거절 사유 (선택)
     * @return 기록한 반환 작업 ID (커밋 후 실행한다)
     */
    @Transactional
    public Long reject(Long guestId, Long requestId, String reason) {
        appearanceUserSupport.getParticipant(guestId);
        AppearanceRequest request = appearanceRequestSupport.getById(requestId);
        request.validateViewableBy(guestId);

        LocalDateTime now = LocalDateTime.now(clock);
        request.reject(guestId, reason, now);
        return appearanceRequestReleaseRecorder.applyAndRecordRelease(request, HpTaskReason.REQUEST_REJECTED, now);
    }
}
