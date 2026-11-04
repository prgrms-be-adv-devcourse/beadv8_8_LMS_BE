package com.hapbang.appearance.app;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.appearance.config.AppearanceBatchProperties;
import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.domain.Appearance;
import com.hapbang.appearance.domain.AppearanceReview;
import com.hapbang.appearance.domain.AppearanceReviewPolicy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 작성 기한이 지난 리뷰 공개 (배치, #515). 작성 기한을 켰을 때, 기한이 지나도록 한쪽만 쓴 리뷰를 공개하고 공개 이벤트를 발행한다.
 * 기한이 지나면 상대는 더 쓸 수 없어서, 이 배치가 없으면 먼저 쓴 리뷰가 영영 비공개로 남는다.
 * <ul>
 *     <li>작성 기한 설정({@code appearance.review.write-period})이 비어 있으면(무기한) 아무것도 하지 않는다. 이 설정 하나로 켜고 끈다.</li>
 *     <li>대상: 공개 전 리뷰가 있고 출연 확인 시각 + 작성 기한 ≤ 지금인 출연. 출연 확인이 오래된 순으로 최대 {@code batchSize}개 출연.</li>
 *     <li>출연마다 [트랜잭션] 출연 배타 잠금 → 기한이 지났는지 다시 확인 → 공개 전 리뷰를 공개하고 리뷰마다 공개 이벤트 발행 (커밋).
 *     작성 API와 같이 출연 행을 먼저 잠그므로 사용자 작성과 겹쳐도 차례로 처리되고, 나중 처리는 이미 공개된 리뷰를 다시 공개하지 않는다.</li>
 *     <li>출연마다 예외를 잡아 다음 출연을 계속 처리한다. 나머지는 다음 주기에 처리한다.</li>
 * </ul>
 * 트랜잭션은 같은 클래스 안 처리라 {@link TransactionTemplate}으로 연다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppearanceReviewExpirePublishUseCase {

    private final AppearanceSupport appearanceSupport;
    private final AppearanceReviewSupport appearanceReviewSupport;
    private final AppearanceReviewPolicy appearanceReviewPolicy;
    private final AppearanceBatchProperties batchProperties;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher eventPublisher;
    @Qualifier(AppearanceConfig.CLOCK)
    private final Clock clock;

    /**
     * @return 공개한 리뷰 수 (작성 기한이 없으면 0)
     */
    public int publishDue() {
        if (!appearanceReviewPolicy.hasWritePeriod()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime confirmedCutoff = now.minus(appearanceReviewPolicy.writePeriod());
        List<Long> appearanceIds = appearanceReviewSupport.findAppearanceIdsWithUnpublishedConfirmedUpTo(
                confirmedCutoff, batchProperties.batchSize());
        int published = 0;
        for (Long appearanceId : appearanceIds) {
            try {
                Integer count = transactionTemplate.execute(status -> publish(appearanceId, now));
                if (count != null) {
                    published += count;
                }
            } catch (RuntimeException exception) {
                log.warn("작성 기한 만료 리뷰 공개 중 예외, 다음 출연으로 넘어간다 appearanceId={}", appearanceId, exception);
            }
        }
        if (published > 0) {
            log.info("작성 기한 만료 리뷰 공개 count={}", published);
        }
        return published;
    }

    /** 기한이 지난 출연의 공개 전 리뷰를 공개하고 이벤트를 발행한다. 공개한 리뷰 수 */
    private int publish(Long appearanceId, LocalDateTime now) {
        Appearance appearance = appearanceSupport.getForUpdate(appearanceId);
        if (!appearance.isReviewWritePeriodEnded(appearanceReviewPolicy, now)) {
            return 0;
        }
        int count = 0;
        for (AppearanceReview review : appearanceReviewSupport.findByAppearance(appearanceId)) {
            if (review.isPublished()) {
                continue;
            }
            review.publish(now);
            appearanceReviewSupport.flush();
            // 회원 모듈이 평점을 계산하도록 알린다 (커밋 뒤 Kafka, #513)
            eventPublisher.publishEvent(review.toPublishedEvent());
            count++;
        }
        return count;
    }
}
