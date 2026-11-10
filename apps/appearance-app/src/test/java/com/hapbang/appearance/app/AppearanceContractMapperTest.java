package com.hapbang.appearance.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.appearance.domain.Appearance;
import com.hapbang.appearance.domain.AppearanceApplication;
import com.hapbang.appearance.domain.AppearanceCancelRequest;
import com.hapbang.appearance.domain.AppearancePolicy;
import com.hapbang.appearance.domain.AppearanceRecruit;
import com.hapbang.appearance.domain.AppearanceRequest;
import com.hapbang.appearance.domain.AppearanceReview;
import com.hapbang.appearance.domain.AppearanceStatus;
import com.hapbang.appearance.fixture.AppearanceRecruitFixture;
import com.hapbang.appearance.fixture.AppearanceRequestFixture;
import com.hapbang.shared.appearance.domain.AppearanceResult;
import com.hapbang.shared.appearance.dto.AppearanceApplicationDto;
import com.hapbang.shared.appearance.dto.AppearanceCancelRequestDto;
import com.hapbang.shared.appearance.dto.AppearanceParticipantRole;
import com.hapbang.shared.appearance.dto.AppearanceRequestDto;
import com.hapbang.shared.appearance.dto.AppearanceReviewSyncDto;
import com.hapbang.shared.appearance.dto.AppearanceSyncDto;
import com.hapbang.shared.appearance.event.AppearanceChangedEvent;
import com.hapbang.shared.appearance.event.AppearanceReviewPublishedEvent;

/**
 * 출연 도메인 → 다른 앱에 보내는 shared DTO·이벤트 변환 (#825). 도메인 테스트에 있던 변환 검증을 옮겼다. 값은 옮기기 전과 같다.
 */
class AppearanceContractMapperTest {

    private static final Long HOST = 1L;
    private static final Long GUEST = 2L;
    private static final LocalDateTime SENT_AT = LocalDateTime.of(2026, 10, 13, 12, 0);
    private static final LocalDateTime ACCEPTED_AT = SENT_AT.plusDays(1);
    /** 방송 예정(SENT_AT + 7일) 한 시간 뒤 */
    private static final LocalDateTime CONFIRMED_AT = SENT_AT.plusDays(7).plusHours(1);
    private static final AppearancePolicy POLICY = new AppearancePolicy(20_000, 30, Duration.ofDays(7), 2, 10);

    /** 보낸 요청서 (ID 12) */
    private static AppearanceRequest requested() {
        AppearanceRequest request = AppearanceRequest.create(HOST, GUEST, AppearanceRequestFixture.content(SENT_AT),
                POLICY, SENT_AT);
        request.markRequested();
        ReflectionTestUtils.setField(request, "id", 12L);
        ReflectionTestUtils.setField(request, "version", 0L);
        return request;
    }

    /** 요청서에서 성립한 출연. ID·version은 flush 전처럼 비어 있다 */
    private static Appearance established() {
        AppearanceRequest request = requested();
        request.accept(GUEST, 0L, ACCEPTED_AT);
        return Appearance.establishFrom(request, ACCEPTED_AT);
    }

    /** 호스트가 확인한 출연 (ID 30) */
    private static Appearance confirmed() {
        Appearance appearance = established();
        ReflectionTestUtils.setField(appearance, "id", 30L);
        appearance.confirmByHost(HOST, POLICY, CONFIRMED_AT);
        return appearance;
    }

    /** 모집 중인 모집글 */
    private static AppearanceRecruit recruiting() {
        AppearanceRecruit recruit = AppearanceRecruit.create(HOST,
                AppearanceRecruitFixture.content(SENT_AT, 30_000, 3), POLICY, SENT_AT);
        recruit.markRecruiting();
        return recruit;
    }

    @Nested
    @DisplayName("출연 동기화 사본·변경 이벤트 (#713)")
    class AppearanceSync {

        @Test
        @DisplayName("ID·호스트·게스트·공개 결과·방송 예정 일시·version. 변경 이벤트도 같은 값을 감싼다")
        void syncDto() {
            Appearance appearance = established();
            ReflectionTestUtils.setField(appearance, "id", 30L);
            ReflectionTestUtils.setField(appearance, "version", 3L);

            AppearanceSyncDto expected = new AppearanceSyncDto(30L, HOST, GUEST, AppearanceResult.SCHEDULED,
                    appearance.getScheduledAt(), 3L);
            assertThat(AppearanceContractMapper.toSyncDto(appearance)).isEqualTo(expected);
            assertThat(AppearanceContractMapper.toChangedEvent(appearance))
                    .isEqualTo(new AppearanceChangedEvent(expected));
        }

        @Test
        @DisplayName("방송 예정 일시는 DB 컬럼과 같은 마이크로초까지만 둔다 (이벤트 값 = 내부 API 값)")
        void scheduledAtMicros() {
            Appearance appearance = established();
            ReflectionTestUtils.setField(appearance, "id", 30L);
            ReflectionTestUtils.setField(appearance, "version", 0L);
            LocalDateTime scheduledAt = appearance.getScheduledAt();
            ReflectionTestUtils.setField(appearance, "scheduledAt", scheduledAt.plusNanos(1_234_567));

            assertThat(AppearanceContractMapper.toSyncDto(appearance).scheduledAt())
                    .isEqualTo(scheduledAt.plusNanos(1_234_000));
        }

        @Test
        @DisplayName("DB에 반영되기 전(ID·version 없음)에는 만들 수 없다 (flush 전에 만든 이벤트를 막는다)")
        void needsFlush() {
            Appearance appearance = established();

            assertThatThrownBy(() -> AppearanceContractMapper.toSyncDto(appearance))
                    .isInstanceOf(IllegalStateException.class);
            ReflectionTestUtils.setField(appearance, "id", 30L);
            assertThatThrownBy(() -> AppearanceContractMapper.toChangedEvent(appearance))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("출연 상태 → 공개 결과 (#713)")
    class Result {

        private static final Map<AppearanceStatus, AppearanceResult> EXPECTED = new EnumMap<>(Map.of(
                AppearanceStatus.ESTABLISHED, AppearanceResult.SCHEDULED,
                AppearanceStatus.CANCEL_REQUESTED, AppearanceResult.SCHEDULED,
                AppearanceStatus.CONFIRMED, AppearanceResult.COMPLETED,
                AppearanceStatus.CANCELED_BY_HOST, AppearanceResult.CANCELED,
                AppearanceStatus.CANCELED_BY_AGREEMENT, AppearanceResult.CANCELED,
                AppearanceStatus.ABSENT, AppearanceResult.NO_SHOW));

        @ParameterizedTest
        @EnumSource(AppearanceStatus.class)
        @DisplayName("모든 상태가 정해진 공개 결과로 바뀐다 (상태를 더하면 이 표에도 더해야 통과한다)")
        void everyStatus(AppearanceStatus status) {
            assertThat(EXPECTED).containsKey(status);
            assertThat(AppearanceContractMapper.toResult(status)).isEqualTo(EXPECTED.get(status));
        }

        @Test
        @DisplayName("공개 결과 4가지가 모두 쓰인다")
        void everyResultUsed() {
            assertThat(EXPECTED.values()).containsOnly(AppearanceResult.values());
        }
    }

    @Nested
    @DisplayName("리뷰 공개 이벤트 (#513)·동기화 사본 (#713)")
    class Review {

        @Test
        @DisplayName("공개된 리뷰는 본문 없이 리뷰·출연·쓴 사람·받은 사람·역할·별점·공개 시각을 담는다")
        void published() {
            AppearanceReview review = AppearanceReview.write(confirmed(), GUEST, 3, "본문은 이벤트에 없음");
            ReflectionTestUtils.setField(review, "id", 77L);
            review.publish(CONFIRMED_AT.plusDays(1));

            assertThat(AppearanceContractMapper.toPublishedEvent(review)).isEqualTo(new AppearanceReviewPublishedEvent(
                    77L, 30L, GUEST, HOST, AppearanceParticipantRole.HOST, 3, CONFIRMED_AT.plusDays(1)));
        }

        @Test
        @DisplayName("동기화 사본은 리뷰·출연·받은 사람·별점만 담고, 공개 이벤트의 같은 이름 필드와 값이 같다")
        void syncDto() {
            AppearanceReview review = AppearanceReview.write(confirmed(), GUEST, 3, "본문은 사본에 없음");
            ReflectionTestUtils.setField(review, "id", 77L);
            review.publish(CONFIRMED_AT.plusDays(1));

            AppearanceReviewSyncDto sync = AppearanceContractMapper.toSyncDto(review);
            AppearanceReviewPublishedEvent published = AppearanceContractMapper.toPublishedEvent(review);

            assertThat(sync).isEqualTo(new AppearanceReviewSyncDto(77L, 30L, HOST, 3));
            assertThat(sync).isEqualTo(new AppearanceReviewSyncDto(published.reviewId(), published.appearanceId(),
                    published.revieweeUserId(), published.rating()));
        }

        @Test
        @DisplayName("공개 전 리뷰로 공개 이벤트·동기화 사본을 만들면 IllegalStateException")
        void notPublished() {
            AppearanceReview review = AppearanceReview.write(confirmed(), HOST, 5, null);

            assertThatThrownBy(() -> AppearanceContractMapper.toPublishedEvent(review))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> AppearanceContractMapper.toSyncDto(review))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("알림 이벤트의 요청서·지원·취소 요청 정보")
    class Notification {

        @Test
        @DisplayName("요청서: 요청서 ID·호스트·게스트·제목·출연료·방송 예정 일시·수락 기한")
        void request() {
            AppearanceRequest request = requested();

            AppearanceRequestDto dto = AppearanceContractMapper.toDto(request);

            assertThat(dto.requestId()).isEqualTo(12L);
            assertThat(dto.hostId()).isEqualTo(HOST);
            assertThat(dto.guestId()).isEqualTo(GUEST);
            assertThat(dto.title()).isEqualTo(request.getTitle());
            assertThat(dto.fee()).isEqualTo(AppearanceRequestFixture.FEE);
            assertThat(dto.scheduledAt()).isEqualTo(request.getScheduledAt());
            assertThat(dto.acceptDeadline()).isEqualTo(request.getAcceptDeadline());
        }

        @Test
        @DisplayName("지원: 모집글 제목·호스트를 함께 담는다. 다른 모집글을 주면 IllegalArgumentException")
        void application() {
            AppearanceRecruit recruit = recruiting();
            AppearanceApplication application = AppearanceApplication.apply(recruit, GUEST, "지원합니다.", SENT_AT);

            AppearanceApplicationDto dto = AppearanceContractMapper.toDto(application, recruit);

            assertThat(dto.recruitTitle()).isEqualTo(recruit.getTitle());
            assertThat(dto.hostId()).isEqualTo(HOST);
            assertThat(dto.guestId()).isEqualTo(GUEST);
            AppearanceRecruit other = recruiting();
            ReflectionTestUtils.setField(other, "id", 999L);
            assertThatThrownBy(() -> AppearanceContractMapper.toDto(application, other))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("취소 요청: 취소 요청 ID·출연 ID·제목·호스트·게스트·순번·방송 예정 일시 (사유는 담지 않음)")
        void cancelRequest() {
            Appearance appearance = established();
            ReflectionTestUtils.setField(appearance, "id", 30L);
            AppearanceCancelRequest cancelRequest = AppearanceCancelRequest.request(appearance, GUEST, "일정이 생겼습니다.",
                    0, POLICY, ACCEPTED_AT);

            AppearanceCancelRequestDto dto = AppearanceContractMapper.toDto(cancelRequest, "주말 합방",
                    appearance.getScheduledAt());

            assertThat(dto.appearanceId()).isEqualTo(30L);
            assertThat(dto.title()).isEqualTo("주말 합방");
            assertThat(dto.hostId()).isEqualTo(HOST);
            assertThat(dto.guestId()).isEqualTo(GUEST);
            assertThat(dto.sequence()).isEqualTo(1);
            assertThat(dto.scheduledAt()).isEqualTo(appearance.getScheduledAt());
        }
    }
}
