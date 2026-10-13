package com.hapbang.appearance.app;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearancePolicy;
import com.hapbang.appearance.domain.AppearanceRequest;
import com.hapbang.appearance.domain.AppearanceRequestContent;

import lombok.RequiredArgsConstructor;

/**
 * 출연 요청서 저장 (보내기의 첫 단계). 자격·값·중복을 확인하고 {@code HOLD_PENDING}으로 저장한다.
 * <p>
 * 보내기는 "저장 → HP 보류 → 상태 변경"을 각각 따로 커밋해야 해서 Facade가 트랜잭션을 열지 않는다.
 * 그래서 이 단계는 여기서 직접 트랜잭션을 연다.
 */
@Service
@RequiredArgsConstructor
public class AppearanceRequestCreateUseCase {

    /** 진행 중 요청 1건 제한을 거는 DB 부분 유니크 인덱스 이름 (마이그레이션과 같아야 한다) */
    static final String PENDING_HOST_GUEST_INDEX = "uk_appearance_requests_pending_host_guest";

    private final AppearanceRequestSupport appearanceRequestSupport;
    private final AppearanceUserSupport appearanceUserSupport;
    private final AppearancePolicy appearancePolicy;
    @Qualifier(AppearanceConfig.CLOCK)
    private final Clock clock;

    /**
     * @throws AppearanceException 자격·값 오류, 진행 중인 요청 중복({@code DUPLICATE_APPEARANCE_REQUEST})
     */
    @Transactional
    public AppearanceRequest createPending(Long hostId, Long guestId, AppearanceRequestContent content) {
        appearanceUserSupport.getParticipant(hostId);
        LocalDateTime now = LocalDateTime.now(clock);
        AppearanceRequest request = AppearanceRequest.create(hostId, guestId, content, appearancePolicy, now);
        appearanceUserSupport.getGuest(guestId);

        if (appearanceRequestSupport.existsInProgress(hostId, guestId)) {
            throw new AppearanceException(AppearanceErrorCode.DUPLICATE_APPEARANCE_REQUEST);
        }
        try {
            return appearanceRequestSupport.saveNow(request);
        } catch (DataIntegrityViolationException exception) {
            // 위 확인을 동시에 통과한 요청은 DB 부분 유니크 인덱스에서 막힌다.
            if (isPendingHostGuestConflict(exception)) {
                throw new AppearanceException(AppearanceErrorCode.DUPLICATE_APPEARANCE_REQUEST);
            }
            throw exception;
        }
    }

    private boolean isPendingHostGuestConflict(DataIntegrityViolationException exception) {
        String message = exception.getMostSpecificCause().getMessage();
        return message != null && message.contains(PENDING_HOST_GUEST_INDEX);
    }
}
