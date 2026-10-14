package com.hapbang.appearance.app;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.domain.Appearance;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceRequest;

import lombok.RequiredArgsConstructor;

/**
 * 출연 요청 수락 (게스트). 요청서를 {@code ACCEPTED}로 바꾸고 출연을 만든다 (출연 성립). HP는 이동하지 않는다.
 * 트랜잭션은 호출한 {@link AppearanceRequestFacade}가 연다.
 */
@Service
@RequiredArgsConstructor
public class AppearanceRequestAcceptUseCase {

    private final AppearanceRequestSupport appearanceRequestSupport;
    private final AppearanceUserSupport appearanceUserSupport;
    private final AppearanceSupport appearanceSupport;
    @Qualifier(AppearanceConfig.CLOCK)
    private final Clock clock;

    /**
     * @param viewedVersion 게스트가 화면에서 본 요청서 버전
     * @return 성립한 출연
     * @throws AppearanceException 자격·권한·상태·기한·버전 오류, 동시 처리 충돌({@code CONCURRENT_UPDATE})
     */
    public Appearance accept(Long guestId, Long requestId, Long viewedVersion) {
        appearanceUserSupport.getParticipant(guestId);
        AppearanceRequest request = appearanceRequestSupport.getById(requestId);
        request.validateViewableBy(guestId);
        // 요청을 보낸 뒤 호스트가 정지·탈퇴했으면 출연을 맺지 않는다
        appearanceUserSupport.getHost(request.getHostId());

        LocalDateTime now = LocalDateTime.now(clock);
        request.accept(guestId, viewedVersion, now);
        try {
            appearanceRequestSupport.flush();
        } catch (ObjectOptimisticLockingFailureException exception) {
            // 같은 요청서를 동시에 수락·거절·취소하면 먼저 반영된 쪽만 성공한다
            throw new AppearanceException(AppearanceErrorCode.CONCURRENT_UPDATE);
        }
        return appearanceSupport.saveNow(Appearance.establishFrom(request, now));
    }
}
