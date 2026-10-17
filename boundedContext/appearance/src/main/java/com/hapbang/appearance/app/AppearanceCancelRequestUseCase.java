package com.hapbang.appearance.app;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.domain.Appearance;
import com.hapbang.appearance.domain.AppearanceCancelRequest;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearancePolicy;
import com.hapbang.shared.appearance.event.AppearanceCancelRequestedEvent;

import lombok.RequiredArgsConstructor;

/**
 * 출연 취소 요청 (게스트). 트랜잭션은 호출한 {@link AppearanceFacade}가 연다.
 * <ul>
 *     <li>출연을 배타 잠금으로 읽는다. 같은 출연에 동시에 요청해도 하나만 받고, 나중 요청은 {@code CANCEL_REQUEST_ALREADY_PENDING}이다.
 *     처리 중인 요청은 출연당 하나라는 DB 부분 유니크 인덱스가 최종으로 막는다.</li>
 *     <li>요청이 저장되면 취소 요청 도착 이벤트({@link AppearanceCancelRequestedEvent})를 발행한다 (ISSUE.md APR-31 K7).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AppearanceCancelRequestUseCase {

    private final AppearanceSupport appearanceSupport;
    private final AppearanceCancelRequestSupport appearanceCancelRequestSupport;
    private final AppearanceUserSupport appearanceUserSupport;
    private final AppearanceTitleSupport appearanceTitleSupport;
    private final AppearancePolicy appearancePolicy;
    private final ApplicationEventPublisher eventPublisher;
    @Qualifier(AppearanceConfig.CLOCK)
    private final Clock clock;

    /**
     * @throws AppearanceException 게스트 자격, 없거나 볼 수 없는 출연({@code APPEARANCE_NOT_FOUND}), 사유 없음·500자 초과,
     *                             게스트가 아님, 처리 중인 요청 있음, 성립 상태 아님, 방송 예정 일시 지남, 횟수 초과
     */
    public void request(Long guestId, Long appearanceId, String reason) {
        appearanceUserSupport.getParticipant(guestId);
        Appearance appearance = appearanceSupport.getForUpdate(appearanceId);
        appearance.validateViewableBy(guestId);

        int previousCount = appearanceCancelRequestSupport.countByAppearance(appearanceId);
        AppearanceCancelRequest cancelRequest = AppearanceCancelRequest.request(appearance, guestId, reason,
                previousCount, appearancePolicy, LocalDateTime.now(clock));
        AppearanceCancelRequest saved = appearanceCancelRequestSupport.save(cancelRequest);
        appearanceSupport.flushOrConflict();

        // 호스트에게 취소 요청 도착을 알린다. 받는 쪽은 커밋 뒤에 처리한다 (@ApplicationModuleListener)
        eventPublisher.publishEvent(new AppearanceCancelRequestedEvent(
                saved.toDto(appearanceTitleSupport.titleOf(appearance), appearance.getScheduledAt())));
    }
}
