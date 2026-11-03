package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.member.domain.AppearanceRatingSummary;
import com.hapbang.member.domain.RatingSummary;
import com.hapbang.member.domain.User;
import com.hapbang.member.fixture.MemberFixture;
import com.hapbang.member.out.AppearanceReviewReceiptRepository;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.appearance.dto.AppearanceParticipantRole;
import com.hapbang.shared.appearance.event.AppearanceReviewPublishedEvent;

/** 출연 리뷰 공개 이벤트를 회원 출연 평점에 반영한다 (#555). 실제 PostgreSQL로 중복 제거와 원자적 증가를 확인한다 */
@MemberIntegrationTest
class MemberAppearanceRatingIntegrationTest {

    private static final AtomicLong REVIEW_ID = new AtomicLong(1);

    @Autowired
    MemberAppearanceRatingFacade memberAppearanceRatingFacade;

    @Autowired
    MemberAppearanceRatingUseCase memberAppearanceRatingUseCase;

    @Autowired
    AppearanceReviewReceiptRepository receiptRepository;

    @Autowired
    UserRepository userRepository;

    @Test
    @DisplayName("받은 역할별로 쌓고 전체는 둘을 합친다")
    void applyByRole() {
        User user = userRepository.save(MemberFixture.user());

        apply(review(user, AppearanceParticipantRole.HOST, 5));
        apply(review(user, AppearanceParticipantRole.HOST, 4));
        apply(review(user, AppearanceParticipantRole.GUEST, 3));

        assertThat(memberAppearanceRatingUseCase.find(user.getId())).isEqualTo(new AppearanceRatingSummary(
                new RatingSummary(4.0, 3),
                new RatingSummary(4.5, 2),
                new RatingSummary(3.0, 1)));
    }

    @Test
    @DisplayName("같은 리뷰가 다시 오면 한 번만 반영한다")
    void duplicate() {
        User user = userRepository.save(MemberFixture.user());
        AppearanceReviewPublishedEvent event = review(user, AppearanceParticipantRole.GUEST, 4);

        assertThat(memberAppearanceRatingFacade.applyReviewPublished(event)).isTrue();
        assertThat(memberAppearanceRatingFacade.applyReviewPublished(event)).isFalse();

        assertThat(memberAppearanceRatingUseCase.find(user.getId()).total()).isEqualTo(new RatingSummary(4.0, 1));
        assertThat(receiptRepository.findById(event.reviewId())).hasValueSatisfying(receipt -> {
            assertThat(receipt.getRevieweeId()).isEqualTo(user.getId());
            assertThat(receipt.getRevieweeRole()).isEqualTo(AppearanceParticipantRole.GUEST);
            assertThat(receipt.getRating()).isEqualTo(4);
        });
    }

    @Test
    @DisplayName("받은 리뷰가 없으면 모두 비어 있다")
    void none() {
        User user = userRepository.save(MemberFixture.user());

        assertThat(memberAppearanceRatingUseCase.find(user.getId())).isEqualTo(AppearanceRatingSummary.EMPTY);
    }

    @Test
    @DisplayName("여러 회원을 한 번에 읽으면 리뷰를 받은 회원만 담긴다")
    void findAll() {
        User rated = userRepository.save(MemberFixture.user());
        User unrated = userRepository.save(MemberFixture.user());
        apply(review(rated, AppearanceParticipantRole.GUEST, 5));

        assertThat(memberAppearanceRatingUseCase.findAll(List.of(rated.getId(), unrated.getId())))
                .containsOnlyKeys(rated.getId());
    }

    @Test
    @DisplayName("같은 회원의 다른 리뷰가 동시에 와도 합계가 맞는다")
    void concurrentReviews() throws Exception {
        User user = userRepository.save(MemberFixture.user());
        List<AppearanceReviewPublishedEvent> events = IntStream.range(0, 10)
                .mapToObj(i -> review(user, AppearanceParticipantRole.GUEST, 4))
                .toList();

        runTogether(events.stream().<Callable<Boolean>>map(event -> () -> apply(event)).toList());

        assertThat(memberAppearanceRatingUseCase.find(user.getId()).asGuest()).isEqualTo(new RatingSummary(4.0, 10));
    }

    @Test
    @DisplayName("같은 리뷰가 동시에 와도 한 번만 반영한다")
    void concurrentDuplicate() throws Exception {
        User user = userRepository.save(MemberFixture.user());
        AppearanceReviewPublishedEvent event = review(user, AppearanceParticipantRole.HOST, 5);

        List<Boolean> applied = runTogether(List.of(() -> apply(event), () -> apply(event), () -> apply(event)));

        assertThat(applied).containsOnlyOnce(true);
        assertThat(memberAppearanceRatingUseCase.find(user.getId()).asHost()).isEqualTo(new RatingSummary(5.0, 1));
    }

    private boolean apply(AppearanceReviewPublishedEvent event) {
        return memberAppearanceRatingFacade.applyReviewPublished(event);
    }

    private static AppearanceReviewPublishedEvent review(User reviewee, AppearanceParticipantRole role, int rating) {
        long reviewId = REVIEW_ID.getAndIncrement();
        return new AppearanceReviewPublishedEvent(reviewId, 100L + reviewId, 999L, reviewee.getId(), role, rating,
                LocalDateTime.now());
    }

    /** 모두 준비된 뒤 한꺼번에 출발시킨다 */
    private static <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(tasks.size())) {
            List<Future<T>> futures = tasks.stream()
                    .map(task -> executor.submit(() -> {
                        start.await();
                        return task.call();
                    }))
                    .toList();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        }
    }
}
