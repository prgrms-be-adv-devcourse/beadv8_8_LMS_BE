package com.hapbang.appearance.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.appearance.app.dto.AppearanceRequestResDto;
import com.hapbang.appearance.config.AppearanceBatchProperties;
import com.hapbang.appearance.domain.AppearanceReviewPolicy;
import com.hapbang.appearance.fixture.AppearanceRequestFixture;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.appearance.support.AppearanceIntegrationTest;
import com.hapbang.appearance.support.AppearanceTestClock;
import com.hapbang.shared.appearance.dto.AppearanceParticipantRole;
import com.hapbang.shared.appearance.event.AppearanceReviewPublishedEvent;

/**
 * 작성 기한 만료 리뷰 공개 배치 (#515)를 DB로 확인한다.
 * <ul>
 *     <li>운영 설정은 작성 기한이 비어 있어(무기한) 등록된 배치는 아무것도 하지 않는다. 기한을 켠 경우는 운영 설정을 바꾸지 않고
 *     기한 14일 정책으로 만든 UseCase를 같은 저장소·시계로 직접 부른다.</li>
 *     <li>발행한 공개 이벤트는 UseCase에 넘긴 수집용 발행기로 본다. 동시 실행 테스트에서 다른 스레드의 발행도 함께 모으기 위해서다.</li>
 *     <li>출연은 실제 순서대로 만든다: 요청서 수락(성립) → 시계를 방송 뒤로 → 호스트 확인.</li>
 * </ul>
 */
@AppearanceIntegrationTest
@WithMockUser(roles = "INFLUENCER")
class AppearanceReviewExpirePublishTest {

    private static final Long HOST = 1L;
    private static final Long GUEST = 2L;
    private static final Duration WRITE_PERIOD = Duration.ofDays(14);
    private static final AppearanceReviewPolicy FOURTEEN_DAYS = new AppearanceReviewPolicy(WRITE_PERIOD);

    @Autowired
    AppearanceReviewExpirePublishUseCase registeredUseCase;

    @Autowired
    AppearanceReviewFacade appearanceReviewFacade;

    @Autowired
    AppearanceFacade appearanceFacade;

    @Autowired
    AppearanceRequestFacade appearanceRequestFacade;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    AppearanceSupport appearanceSupport;

    @Autowired
    AppearanceReviewSupport appearanceReviewSupport;

    @Autowired
    AppearanceUserSupport appearanceUserSupport;

    @Autowired
    AppearanceBatchProperties batchProperties;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    AppearanceTestClock clock;

    /** 수동으로 만든 UseCase들이 발행한 공개 이벤트 (여러 스레드) */
    final List<AppearanceReviewPublishedEvent> events = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(HOST));
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(GUEST));
    }

    // ---------------------------------------------------------------------------------------------
    // 준비

    /** 방송 뒤 호스트가 확인한 출연 ID */
    private Long confirmed() {
        AppearanceRequestResDto sent = appearanceRequestFacade.create(HOST, GUEST,
                AppearanceRequestFixture.content(clock.now()));
        Long appearanceId = appearanceRequestFacade.accept(GUEST, sent.id(), sent.version()).appearanceId();
        clock.advanceTo(sent.scheduledAt().plusHours(1));
        appearanceFacade.confirm(HOST, appearanceId);
        return appearanceId;
    }

    /** 기한 14일 정책 배치. 처리 건수는 운영 설정 또는 지정한 값 */
    private AppearanceReviewExpirePublishUseCase batch(int batchSize) {
        AppearanceBatchProperties properties = new AppearanceBatchProperties(batchSize,
                batchProperties.pendingGrace(), batchProperties.holdVoidAfter(), batchProperties.retryBaseDelay(),
                batchProperties.retryMaxDelay(),
                batchProperties.retryMaxAttempts(), batchProperties.retryJitterRatio());
        return new AppearanceReviewExpirePublishUseCase(appearanceSupport, appearanceReviewSupport, FOURTEEN_DAYS,
                properties, transactionTemplate, this::collect, clock);
    }

    private AppearanceReviewExpirePublishUseCase batch() {
        return batch(batchProperties.batchSize());
    }

    /**
     * 기한 없는 정책으로 만든 작성 UseCase. 테스트 시계는 하나라 "기한 직전에 시작한 작성이 잠금을 기다리는 사이 배치가 먼저 공개"하는
     * 경쟁을 시각으로 만들 수 없다. 그래서 작성 쪽만 기한 확인을 통과시키고, 기한이 지난 시각에 배치와 겹치게 실행해 같은 상황을 만든다.
     */
    private Long writeWithoutPeriod(Long reviewerId, Long appearanceId) {
        AppearanceReviewEligibility eligibility = new AppearanceReviewEligibility(appearanceReviewSupport,
                appearanceUserSupport, AppearanceReviewPolicy.unlimited());
        AppearanceReviewWriteUseCase useCase = new AppearanceReviewWriteUseCase(appearanceSupport,
                appearanceReviewSupport, eligibility, this::collect, clock);
        return transactionTemplate.execute(status -> useCase.write(reviewerId, appearanceId, 4, null));
    }

    private void collect(Object event) {
        if (event instanceof AppearanceReviewPublishedEvent published) {
            events.add(published);
        }
    }

    private Long write(Long reviewerId, Long appearanceId, int rating) {
        return appearanceReviewFacade.write(reviewerId, appearanceId, rating, null).id();
    }

    private LocalDateTime confirmedAtOf(Long appearanceId) {
        return jdbcTemplate.queryForObject("select confirmed_at from appearances.appearances where id = ?",
                LocalDateTime.class, appearanceId);
    }

    private LocalDateTime publishedAtOf(Long reviewId) {
        return jdbcTemplate.queryForObject("select published_at from appearances.reviews where id = ?",
                LocalDateTime.class, reviewId);
    }

    private void deadlinePassed(Long appearanceId) {
        clock.advanceTo(confirmedAtOf(appearanceId).plus(WRITE_PERIOD));
    }

    private List<Long> eventReviewIds() {
        List<Long> ids = new ArrayList<>();
        for (AppearanceReviewPublishedEvent event : events) {
            ids.add(event.reviewId());
        }
        return ids;
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("작성 기한이 없으면(운영 설정) 등록된 배치는 1년이 지나도 아무것도 공개하지 않는다")
    void unlimitedDoesNothing() {
        Long appearanceId = confirmed();
        Long reviewId = write(HOST, appearanceId, 5);
        clock.advance(Duration.ofDays(365));

        assertThat(registeredUseCase.publishDue()).isZero();
        assertThat(publishedAtOf(reviewId)).isNull();
    }

    @Test
    @DisplayName("기한 14일: 확인 + 14일 1초 전에는 그대로, 그 시각에는 한쪽 리뷰를 공개하고 이벤트 1개 (받은 사람·별점·공개 시각 = DB)")
    void publishesAfterDeadline() {
        Long appearanceId = confirmed();
        Long reviewId = write(HOST, appearanceId, 5);
        LocalDateTime deadline = confirmedAtOf(appearanceId).plus(WRITE_PERIOD);

        clock.advanceTo(deadline.minusSeconds(1));
        assertThat(batch().publishDue()).isZero();
        assertThat(publishedAtOf(reviewId)).isNull();

        clock.advanceTo(deadline);
        assertThat(batch().publishDue()).isEqualTo(1);

        LocalDateTime publishedAt = publishedAtOf(reviewId);
        assertThat(publishedAt).isNotNull().isAfterOrEqualTo(deadline);
        assertThat(events).containsExactly(new AppearanceReviewPublishedEvent(reviewId, appearanceId, HOST, GUEST,
                AppearanceParticipantRole.GUEST, 5, publishedAt));
    }

    @Test
    @DisplayName("한 번 공개한 리뷰는 다음 실행에서 다시 공개하지 않는다 (이벤트 1개 그대로, 공개 시각 유지)")
    void onlyOnce() {
        Long appearanceId = confirmed();
        Long reviewId = write(GUEST, appearanceId, 3);
        deadlinePassed(appearanceId);
        batch().publishDue();
        LocalDateTime first = publishedAtOf(reviewId);

        clock.advance(Duration.ofMinutes(1));
        assertThat(batch().publishDue()).isZero();

        assertThat(publishedAtOf(reviewId)).isEqualTo(first);
        assertThat(events).hasSize(1);
    }

    @Test
    @DisplayName("둘 다 써서 이미 공개된 출연은 대상이 아니다")
    void alreadyPublishedExcluded() {
        Long appearanceId = confirmed();
        Long hostReview = write(HOST, appearanceId, 5);
        write(GUEST, appearanceId, 4);
        LocalDateTime publishedAt = publishedAtOf(hostReview);
        deadlinePassed(appearanceId);

        assertThat(batch().publishDue()).isZero();
        assertThat(publishedAtOf(hostReview)).isEqualTo(publishedAt);
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("리뷰가 하나도 없는 출연은 대상이 아니다")
    void noReview() {
        Long appearanceId = confirmed();
        deadlinePassed(appearanceId);

        assertThat(batch().publishDue()).isZero();
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("한 번에 처리 건수만큼, 출연 확인이 오래된 순으로 공개하고 나머지는 다음 실행에서 공개한다")
    void batchSizeOldestFirst() {
        Long first = confirmed();
        Long firstReview = write(HOST, first, 5);
        Long second = confirmed();
        Long secondReview = write(HOST, second, 4);
        Long third = confirmed();
        Long thirdReview = write(GUEST, third, 3);
        deadlinePassed(third);

        assertThat(batch(2).publishDue()).isEqualTo(2);
        assertThat(eventReviewIds()).containsExactly(firstReview, secondReview);
        assertThat(publishedAtOf(thirdReview)).isNull();

        assertThat(batch(2).publishDue()).isEqualTo(1);
        assertThat(eventReviewIds()).containsExactly(firstReview, secondReview, thirdReview);
    }

    @Test
    @DisplayName("배치가 먼저 공개한 리뷰는 상대가 나중에 써도 이벤트를 다시 보내지 않는다 (상대 리뷰 이벤트만 1개 더)")
    void writeAfterBatchDoesNotRepublish() {
        Long appearanceId = confirmed();
        Long hostReview = write(HOST, appearanceId, 5);
        deadlinePassed(appearanceId);
        batch().publishDue();
        LocalDateTime publishedByBatch = publishedAtOf(hostReview);

        clock.advance(Duration.ofMinutes(1));
        Long guestReview = writeWithoutPeriod(GUEST, appearanceId);

        assertThat(publishedAtOf(hostReview)).isEqualTo(publishedByBatch);
        assertThat(publishedAtOf(guestReview)).isAfter(publishedByBatch);
        assertThat(eventReviewIds()).containsExactly(hostReview, guestReview);
    }

    @Test
    @DisplayName("배치와 상대 작성이 동시에 일어나도 출연 행 잠금으로 차례로 처리돼, 두 리뷰 모두 공개되고 리뷰마다 이벤트는 정확히 1개다 (5회)")
    void concurrentWithWrite() throws Exception {
        for (int round = 0; round < 5; round++) {
            events.clear();
            Long appearanceId = confirmed();
            Long hostReview = write(HOST, appearanceId, 5);
            deadlinePassed(appearanceId);
            AppearanceReviewExpirePublishUseCase batch = batch();

            List<Object> results = runConcurrently(List.of(
                    () -> batch.publishDue(),
                    () -> writeWithoutPeriod(GUEST, appearanceId)));

            Long guestReview = (Long) results.get(1);
            assertThat(publishedAtOf(hostReview)).isNotNull();
            assertThat(publishedAtOf(guestReview)).isNotNull();
            assertThat(eventReviewIds()).containsExactlyInAnyOrder(hostReview, guestReview);
        }
    }

    private List<Object> runConcurrently(List<Callable<Object>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            executor.shutdown();
        }
    }
}
