package com.hapbang.appearance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.appearance.fixture.AppearanceRequestFixture;
import com.hapbang.shared.appearance.dto.AppearanceParticipantRole;
import com.hapbang.shared.appearance.dto.AppearanceReviewSyncDto;
import com.hapbang.shared.appearance.event.AppearanceReviewPublishedEvent;

/**
 * 출연 리뷰 규칙 (#512, ISSUE.md APR-45): 쓸 수 있는 출연(당사자·확인됨·작성 기한), 별점·본문, 받는 사람, 블라인드 공개.
 */
class AppearanceReviewTest {

    private static final Long HOST = 1L;
    private static final Long GUEST = 2L;
    private static final Long OTHER = 3L;
    private static final LocalDateTime SENT_AT = LocalDateTime.of(2026, 10, 13, 12, 0);
    private static final LocalDateTime ESTABLISHED_AT = SENT_AT.plusDays(1);
    /** 방송 예정 일시 (요청서 픽스처: 보낸 시각 + 7일) */
    private static final LocalDateTime SCHEDULED = SENT_AT.plusDays(7);
    /** 호스트가 출연을 확인한 시각 */
    private static final LocalDateTime CONFIRMED_AT = SCHEDULED.plusHours(1);
    private static final AppearancePolicy POLICY = new AppearancePolicy(20_000, 30, Duration.ofDays(7), 2, 10);
    private static final AppearanceReviewPolicy UNLIMITED = AppearanceReviewPolicy.unlimited();
    private static final AppearanceReviewPolicy FOURTEEN_DAYS = new AppearanceReviewPolicy(Duration.ofDays(14));

    /** 요청서에서 성립한 출연 */
    private static Appearance established() {
        AppearanceRequest request = AppearanceRequest.create(HOST, GUEST,
                AppearanceRequestFixture.content(SENT_AT, 30_000), POLICY, SENT_AT);
        request.markRequested();
        ReflectionTestUtils.setField(request, "id", 12L);
        ReflectionTestUtils.setField(request, "version", 0L);
        request.accept(GUEST, 0L, ESTABLISHED_AT);
        Appearance appearance = Appearance.establishFrom(request, ESTABLISHED_AT);
        ReflectionTestUtils.setField(appearance, "id", 30L);
        return appearance;
    }

    /** 호스트가 확인한 출연 (확인 시각 {@link #CONFIRMED_AT}) */
    private static Appearance confirmed() {
        Appearance appearance = established();
        appearance.confirmByHost(HOST, POLICY, CONFIRMED_AT);
        return appearance;
    }

    private static void assertError(Runnable action, AppearanceErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(AppearanceException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }

    @Nested
    @DisplayName("쓸 수 있는 출연")
    class Reviewable {

        @Test
        @DisplayName("호스트·게스트는 작성자가 될 수 있고, 다른 회원에게는 출연이 없는 것으로 404 APPEARANCE_NOT_FOUND")
        void reviewer() {
            Appearance appearance = confirmed();

            assertThatCode(() -> appearance.validateViewableBy(HOST)).doesNotThrowAnyException();
            assertThatCode(() -> appearance.validateViewableBy(GUEST)).doesNotThrowAnyException();
            assertError(() -> appearance.validateViewableBy(OTHER), AppearanceErrorCode.APPEARANCE_NOT_FOUND);
        }

        @Test
        @DisplayName("호스트 확인·자동 확인된 출연은 쓸 수 있다 (기한 없음이면 확인 1년 뒤에도)")
        void confirmedByHostOrAuto() {
            Appearance byHost = confirmed();
            Appearance auto = established();
            auto.confirmAutomatically(POLICY, POLICY.confirmDeadline(SCHEDULED));

            assertThatCode(() -> byHost.validateReviewable(UNLIMITED, CONFIRMED_AT.plusYears(1)))
                    .doesNotThrowAnyException();
            assertThatCode(() -> auto.validateReviewable(UNLIMITED, POLICY.confirmDeadline(SCHEDULED)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("확인되지 않은 출연(성립·취소 요청 중·불참·호스트 취소·합의 취소)은 409 APPEARANCE_NOT_REVIEWABLE")
        void notConfirmed() {
            Appearance establishedOnly = established();

            Appearance cancelRequested = established();
            cancelRequested.requestCancel(GUEST, 0, 2, SCHEDULED.minusDays(1));

            Appearance absent = established();
            absent.markAbsent(HOST, null, POLICY, CONFIRMED_AT);

            Appearance canceledByHost = established();
            canceledByHost.cancelByHost(HOST, false, null, POLICY, SCHEDULED.minusDays(1));

            Appearance canceledByAgreement = established();
            canceledByAgreement.requestCancel(GUEST, 0, 2, SCHEDULED.minusDays(1));
            canceledByAgreement.agreeCancel(SCHEDULED.minusDays(1));

            for (Appearance appearance : new Appearance[]{establishedOnly, cancelRequested, absent, canceledByHost,
                    canceledByAgreement}) {
                assertError(() -> appearance.validateReviewable(UNLIMITED, CONFIRMED_AT.plusDays(1)),
                        AppearanceErrorCode.APPEARANCE_NOT_REVIEWABLE);
            }
        }

        @Test
        @DisplayName("기한 14일: 확인 시각 + 14일 1초 전까지 쓸 수 있고, 그 시각부터 409 REVIEW_PERIOD_ENDED")
        void writePeriod() {
            Appearance appearance = confirmed();
            LocalDateTime deadline = CONFIRMED_AT.plusDays(14);

            assertThatCode(() -> appearance.validateReviewable(FOURTEEN_DAYS, deadline.minusSeconds(1)))
                    .doesNotThrowAnyException();
            assertError(() -> appearance.validateReviewable(FOURTEEN_DAYS, deadline),
                    AppearanceErrorCode.REVIEW_PERIOD_ENDED);
        }

        @Test
        @DisplayName("확인되지 않은 출연은 기한보다 상태 오류가 먼저다")
        void statusBeforePeriod() {
            Appearance absent = established();
            absent.markAbsent(HOST, null, POLICY, CONFIRMED_AT);

            assertError(() -> absent.validateReviewable(FOURTEEN_DAYS, CONFIRMED_AT.plusDays(30)),
                    AppearanceErrorCode.APPEARANCE_NOT_REVIEWABLE);
        }

        @Test
        @DisplayName("작성 기한 끝남(#515): 기한 없음이면 1년 뒤에도 false, 14일이면 확인 + 14일 1초 전 false·그 시각 true")
        void writePeriodEnded() {
            Appearance appearance = confirmed();
            LocalDateTime deadline = CONFIRMED_AT.plusDays(14);

            assertThat(appearance.isReviewWritePeriodEnded(UNLIMITED, CONFIRMED_AT.plusYears(1))).isFalse();
            assertThat(appearance.isReviewWritePeriodEnded(FOURTEEN_DAYS, deadline.minusSeconds(1))).isFalse();
            assertThat(appearance.isReviewWritePeriodEnded(FOURTEEN_DAYS, deadline)).isTrue();
        }

        @Test
        @DisplayName("작성 기한 끝남(#515): 확인되지 않은 출연(성립·불참)은 기한이 있어도 false")
        void writePeriodEndedOnlyConfirmed() {
            Appearance establishedOnly = established();
            Appearance absent = established();
            absent.markAbsent(HOST, null, POLICY, CONFIRMED_AT);

            assertThat(establishedOnly.isReviewWritePeriodEnded(FOURTEEN_DAYS, CONFIRMED_AT.plusDays(30))).isFalse();
            assertThat(absent.isReviewWritePeriodEnded(FOURTEEN_DAYS, CONFIRMED_AT.plusDays(30))).isFalse();
        }

        @Test
        @DisplayName("상대: 호스트의 상대는 게스트, 게스트의 상대는 호스트, 당사자가 아니면 IllegalArgumentException")
        void counterpart() {
            Appearance appearance = confirmed();

            assertThat(appearance.counterpartOf(HOST)).isEqualTo(GUEST);
            assertThat(appearance.counterpartOf(GUEST)).isEqualTo(HOST);
            assertThatThrownBy(() -> appearance.counterpartOf(OTHER)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("리뷰 작성")
    class Write {

        @Test
        @DisplayName("호스트가 쓰면 받는 사람은 게스트(GUEST), 공개 전")
        void byHost() {
            AppearanceReview review = AppearanceReview.write(confirmed(), HOST, 5, "좋았습니다.");

            assertThat(review.getAppearanceId()).isEqualTo(30L);
            assertThat(review.getReviewerId()).isEqualTo(HOST);
            assertThat(review.getRevieweeId()).isEqualTo(GUEST);
            assertThat(review.getRevieweeRole()).isEqualTo(AppearanceRole.GUEST);
            assertThat(review.getRating()).isEqualTo(5);
            assertThat(review.getContent()).isEqualTo("좋았습니다.");
            assertThat(review.getPublishedAt()).isNull();
            assertThat(review.isPublished()).isFalse();
        }

        @Test
        @DisplayName("게스트가 쓰면 받는 사람은 호스트(HOST)")
        void byGuest() {
            AppearanceReview review = AppearanceReview.write(confirmed(), GUEST, 1, null);

            assertThat(review.getRevieweeId()).isEqualTo(HOST);
            assertThat(review.getRevieweeRole()).isEqualTo(AppearanceRole.HOST);
            assertThat(review.getRating()).isEqualTo(1);
            assertThat(review.getContent()).isNull();
        }

        @ParameterizedTest(name = "별점 {0}")
        @ValueSource(ints = {0, 6, -1})
        @DisplayName("별점이 1~5 밖이면 400 INVALID_REQUEST")
        void ratingOutOfRange(int rating) {
            assertThatThrownBy(() -> AppearanceReview.write(confirmed(), HOST, rating, null))
                    .isInstanceOf(AppearanceException.class)
                    .hasMessage("별점은 1~5 사이의 정수여야 합니다.")
                    .extracting("errorCode").isEqualTo(AppearanceErrorCode.INVALID_REQUEST);
        }

        @Test
        @DisplayName("별점이 없으면 400 INVALID_REQUEST")
        void ratingMissing() {
            assertError(() -> AppearanceReview.write(confirmed(), HOST, null, "본문"),
                    AppearanceErrorCode.INVALID_REQUEST);
        }

        @Test
        @DisplayName("본문: 앞뒤 공백을 지우고, 공백뿐이면 null")
        void contentTrimmed() {
            assertThat(AppearanceReview.write(confirmed(), HOST, 4, "  좋았어요 \n").getContent())
                    .isEqualTo("좋았어요");
            assertThat(AppearanceReview.write(confirmed(), HOST, 4, " \t\n ").getContent()).isNull();
            assertThat(AppearanceReview.write(confirmed(), HOST, 4, "").getContent()).isNull();
        }

        @Test
        @DisplayName("본문은 공백을 지운 뒤 1,000자까지 되고, 1,001자면 400 INVALID_REQUEST")
        void contentLength() {
            String max = "가".repeat(AppearanceReview.CONTENT_MAX);

            assertThat(AppearanceReview.write(confirmed(), HOST, 3, "  " + max + "  ").getContent()).isEqualTo(max);
            assertThatThrownBy(() -> AppearanceReview.write(confirmed(), HOST, 3, max + "가"))
                    .isInstanceOf(AppearanceException.class)
                    .hasMessage("리뷰 본문은 1,000자 이하여야 합니다.")
                    .extracting("errorCode").isEqualTo(AppearanceErrorCode.INVALID_REQUEST);
        }

        @Test
        @DisplayName("당사자가 아닌 작성자로 만들면 IllegalStateException (호출한 쪽 확인 누락)")
        void notParticipant() {
            assertThatThrownBy(() -> AppearanceReview.write(confirmed(), OTHER, 5, null))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("블라인드 공개")
    class Publish {

        @Test
        @DisplayName("공개하면 공개 시각이 채워지고, 다시 공개해도 처음 시각을 그대로 둔다")
        void publishOnce() {
            AppearanceReview review = AppearanceReview.write(confirmed(), HOST, 5, null);
            LocalDateTime first = CONFIRMED_AT.plusDays(1);

            review.publish(first);
            review.publish(first.plusDays(1));

            assertThat(review.isPublished()).isTrue();
            assertThat(review.getPublishedAt()).isEqualTo(first);
        }
    }

    @Nested
    @DisplayName("공개 이벤트 (#513)")
    class PublishedEvent {

        @Test
        @DisplayName("공개된 리뷰는 본문 없이 리뷰·출연·쓴 사람·받은 사람·역할·별점·공개 시각을 담는다")
        void published() {
            AppearanceReview review = AppearanceReview.write(confirmed(), GUEST, 3, "본문은 이벤트에 없음");
            ReflectionTestUtils.setField(review, "id", 77L);
            review.publish(CONFIRMED_AT.plusDays(1));

            assertThat(review.toPublishedEvent()).isEqualTo(new AppearanceReviewPublishedEvent(77L, 30L, GUEST, HOST,
                    AppearanceParticipantRole.HOST, 3, CONFIRMED_AT.plusDays(1)));
        }

        @Test
        @DisplayName("공개 전 리뷰로 만들면 IllegalStateException")
        void notPublished() {
            AppearanceReview review = AppearanceReview.write(confirmed(), HOST, 5, null);

            assertThatThrownBy(review::toPublishedEvent).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("동기화 사본(#713)은 리뷰·출연·받은 사람·별점만 담고, 공개 이벤트의 같은 이름 필드와 값이 같다")
        void syncDto() {
            AppearanceReview review = AppearanceReview.write(confirmed(), GUEST, 3, "본문은 사본에 없음");
            ReflectionTestUtils.setField(review, "id", 77L);
            review.publish(CONFIRMED_AT.plusDays(1));

            AppearanceReviewSyncDto sync = review.toSyncDto();
            AppearanceReviewPublishedEvent published = review.toPublishedEvent();

            assertThat(sync).isEqualTo(new AppearanceReviewSyncDto(77L, 30L, HOST, 3));
            assertThat(sync).isEqualTo(new AppearanceReviewSyncDto(published.reviewId(), published.appearanceId(),
                    published.revieweeUserId(), published.rating()));
        }

        @Test
        @DisplayName("공개 전 리뷰로 동기화 사본을 만들면 IllegalStateException")
        void syncDtoNotPublished() {
            AppearanceReview review = AppearanceReview.write(confirmed(), HOST, 5, null);

            assertThatThrownBy(review::toSyncDto).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("공개 시각은 DB 컬럼과 같은 마이크로초까지만 둔다")
        void publishedAtMicros() {
            AppearanceReview review = AppearanceReview.write(confirmed(), HOST, 5, null);

            review.publish(CONFIRMED_AT.plusNanos(1_234_567));

            assertThat(review.getPublishedAt()).isEqualTo(CONFIRMED_AT.plusNanos(1_234_000));
        }
    }

    @Nested
    @DisplayName("리뷰 정책")
    class Policy {

        @Test
        @DisplayName("기한이 없으면 언제든 쓸 수 있다")
        void unlimited() {
            assertThat(UNLIMITED.hasWritePeriod()).isFalse();
            assertThat(UNLIMITED.isWritable(CONFIRMED_AT, CONFIRMED_AT.plusYears(10))).isTrue();
        }

        @Test
        @DisplayName("기한이 있으면 확인 시각 + 기간 전까지만 쓸 수 있다")
        void withPeriod() {
            assertThat(FOURTEEN_DAYS.hasWritePeriod()).isTrue();
            assertThat(FOURTEEN_DAYS.isWritable(CONFIRMED_AT, CONFIRMED_AT)).isTrue();
            assertThat(FOURTEEN_DAYS.isWritable(CONFIRMED_AT, CONFIRMED_AT.plusDays(14).minusNanos(1))).isTrue();
            assertThat(FOURTEEN_DAYS.isWritable(CONFIRMED_AT, CONFIRMED_AT.plusDays(14))).isFalse();
        }

        @Test
        @DisplayName("기간이 0이거나 음수면 만들 수 없다 (앱이 뜨지 않음)")
        void invalidPeriod() {
            assertThatThrownBy(() -> new AppearanceReviewPolicy(Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new AppearanceReviewPolicy(Duration.ofDays(-1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
