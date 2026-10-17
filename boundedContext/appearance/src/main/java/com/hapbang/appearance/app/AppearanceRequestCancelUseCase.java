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
 * 출연 요청 응답 전 취소 (호스트). 요청서를 {@code CANCELED}로 바꾸고 보류 HP 반환 작업을 기록한다.
 * 출연 성립 후의 호스트 일방 취소(위약금)와 다르다.
 * <p>
 * 반환은 Billing 호출이라 커밋 뒤에 실행해야 해서, Facade가 아니라 여기서 트랜잭션을 연다.
 */
@Service
@RequiredArgsConstructor
public class AppearanceRequestCancelUseCase {

    private final AppearanceRequestSupport appearanceRequestSupport;
    private final AppearanceUserSupport appearanceUserSupport;
    private final AppearanceRequestReleaseRecorder appearanceRequestReleaseRecorder;
    @Qualifier(AppearanceConfig.CLOCK)
    private final Clock clock;

    /**
     * @return 기록한 반환 작업 ID (커밋 후 실행한다)
     */
    @Transactional
    public Long cancel(Long hostId, Long requestId) {
        appearanceUserSupport.getParticipant(hostId);
        AppearanceRequest request = appearanceRequestSupport.getById(requestId);
        request.validateViewableBy(hostId);

        LocalDateTime now = LocalDateTime.now(clock);
        request.cancel(hostId, now);
        return appearanceRequestReleaseRecorder.applyAndRecordRelease(request, HpTaskReason.REQUEST_CANCELED, now);
    }
}
