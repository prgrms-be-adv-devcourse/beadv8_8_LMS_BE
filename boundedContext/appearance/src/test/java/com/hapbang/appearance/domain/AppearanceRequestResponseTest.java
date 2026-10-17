package com.hapbang.appearance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.appearance.fixture.AppearanceRequestFixture;

/**
 * 출연 요청서 응답(수락·거절·응답 전 취소) 규칙. 확인 순서: 행위자 → 상태 → 수락 기한 → (수락이면) 버전.
 */
class AppearanceRequestResponseTest {

    private static final Long HOST = 1L;
    private static final Long GUEST = 2L;
    private static final LocalDateTime SENT_AT = LocalDateTime.of(2026, 10, 13, 12, 0);
    /** 수락 기한: 보낸 시각 + 2일 */
    private static final LocalDateTime DEADLINE = SENT_AT.plusDays(2);
    private static final LocalDateTime BEFORE_DEADLINE = DEADLINE.minusMinutes(1);
    private static final Long VERSION = 3L;
    private static final AppearancePolicy POLICY = new AppearancePolicy(20_000, 30, Duration.ofDays(7), 2, 10);

    private static AppearanceRequest requested() {
        AppearanceRequest request = AppearanceRequest.create(HOST, GUEST, AppearanceRequestFixture.content(SENT_AT),
                POLICY, SENT_AT);
        request.markRequested();
        ReflectionTestUtils.setField(request, "version", VERSION);
        return request;
    }

    private static void assertErrorCode(Runnable action, AppearanceErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(AppearanceException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }

    @Nested
    @DisplayName("수락 (게스트)")
    class Accept {

        @Test
        @DisplayName("게스트가 본 버전 그대로 기한 전에 수락하면 ACCEPTED, 응답 시각 기록")
        void accept() {
            AppearanceRequest request = requested();

            request.accept(GUEST, VERSION, BEFORE_DEADLINE);

            assertThat(request.getStatus()).isEqualTo(AppearanceRequestStatus.ACCEPTED);
            assertThat(request.getRespondedAt()).isEqualTo(BEFORE_DEADLINE);
        }

        @Test
        @DisplayName("본 버전과 지금 버전이 다르면(그사이 수정됨) APPEARANCE_REQUEST_MODIFIED")
        void modified() {
            AppearanceRequest request = requested();

            assertErrorCode(() -> request.accept(GUEST, VERSION - 1, BEFORE_DEADLINE),
                    AppearanceErrorCode.APPEARANCE_REQUEST_MODIFIED);
            assertThat(request.getStatus()).isEqualTo(AppearanceRequestStatus.REQUESTED);
        }

        @Test
        @DisplayName("호스트가 수락하면 NOT_REQUEST_GUEST")
        void host() {
            assertErrorCode(() -> requested().accept(HOST, VERSION, BEFORE_DEADLINE),
                    AppearanceErrorCode.NOT_REQUEST_GUEST);
        }

        @Test
        @DisplayName("수락 기한과 같거나 지난 시각이면 APPEARANCE_REQUEST_EXPIRED (DB 상태가 아직 REQUESTED여도)")
        void expired() {
            assertErrorCode(() -> requested().accept(GUEST, VERSION, DEADLINE),
                    AppearanceErrorCode.APPEARANCE_REQUEST_EXPIRED);
            assertErrorCode(() -> requested().accept(GUEST, VERSION, DEADLINE.plusSeconds(1)),
                    AppearanceErrorCode.APPEARANCE_REQUEST_EXPIRED);
        }
    }

    @Nested
    @DisplayName("거절 (게스트)")
    class Reject {

        @Test
        @DisplayName("사유와 함께 거절하면 REJECTED, 사유·응답 시각 기록")
        void rejectWithReason() {
            AppearanceRequest request = requested();

            request.reject(GUEST, "해당 일정에 다른 방송이 있습니다.", BEFORE_DEADLINE);

            assertThat(request.getStatus()).isEqualTo(AppearanceRequestStatus.REJECTED);
            assertThat(request.getRejectReason()).isEqualTo("해당 일정에 다른 방송이 있습니다.");
            assertThat(request.getRespondedAt()).isEqualTo(BEFORE_DEADLINE);
        }

        @Test
        @DisplayName("사유 없이도 거절할 수 있다")
        void rejectWithoutReason() {
            AppearanceRequest request = requested();

            request.reject(GUEST, null, BEFORE_DEADLINE);

            assertThat(request.getStatus()).isEqualTo(AppearanceRequestStatus.REJECTED);
            assertThat(request.getRejectReason()).isNull();
        }

        @Test
        @DisplayName("사유 500자는 통과, 501자는 INVALID_REQUEST")
        void reasonLength() {
            requested().reject(GUEST, "가".repeat(500), BEFORE_DEADLINE);

            assertErrorCode(() -> requested().reject(GUEST, "가".repeat(501), BEFORE_DEADLINE),
                    AppearanceErrorCode.INVALID_REQUEST);
        }

        @Test
        @DisplayName("호스트가 거절하면 NOT_REQUEST_GUEST, 기한이 지났으면 APPEARANCE_REQUEST_EXPIRED")
        void hostOrExpired() {
            assertErrorCode(() -> requested().reject(HOST, null, BEFORE_DEADLINE),
                    AppearanceErrorCode.NOT_REQUEST_GUEST);
            assertErrorCode(() -> requested().reject(GUEST, null, DEADLINE),
                    AppearanceErrorCode.APPEARANCE_REQUEST_EXPIRED);
        }
    }

    @Nested
    @DisplayName("응답 전 취소 (호스트)")
    class Cancel {

        @Test
        @DisplayName("호스트가 기한 전에 취소하면 CANCELED, 취소 시각 기록")
        void cancel() {
            AppearanceRequest request = requested();

            request.cancel(HOST, BEFORE_DEADLINE);

            assertThat(request.getStatus()).isEqualTo(AppearanceRequestStatus.CANCELED);
            assertThat(request.getCanceledAt()).isEqualTo(BEFORE_DEADLINE);
        }

        @Test
        @DisplayName("게스트가 취소하면 NOT_REQUEST_HOST (취소 기준 메시지)")
        void guest() {
            assertThatThrownBy(() -> requested().cancel(GUEST, BEFORE_DEADLINE))
                    .isInstanceOf(AppearanceException.class)
                    .hasMessage("출연 요청서를 보낸 호스트만 취소할 수 있습니다.")
                    .extracting("errorCode").isEqualTo(AppearanceErrorCode.NOT_REQUEST_HOST);
        }

        @Test
        @DisplayName("기한이 지났으면 APPEARANCE_REQUEST_EXPIRED")
        void expired() {
            assertErrorCode(() -> requested().cancel(HOST, DEADLINE), AppearanceErrorCode.APPEARANCE_REQUEST_EXPIRED);
        }
    }

    @Test
    @DisplayName("이미 응답이 끝난 요청서에 다시 응답하면 APPEARANCE_REQUEST_NOT_AWAITING_RESPONSE")
    void alreadyResponded() {
        AppearanceRequest rejected = requested();
        rejected.reject(GUEST, null, BEFORE_DEADLINE);

        assertErrorCode(() -> rejected.reject(GUEST, null, BEFORE_DEADLINE),
                AppearanceErrorCode.APPEARANCE_REQUEST_NOT_AWAITING_RESPONSE);
        assertErrorCode(() -> rejected.accept(GUEST, VERSION, BEFORE_DEADLINE),
                AppearanceErrorCode.APPEARANCE_REQUEST_NOT_AWAITING_RESPONSE);
        assertErrorCode(() -> rejected.cancel(HOST, BEFORE_DEADLINE),
                AppearanceErrorCode.APPEARANCE_REQUEST_NOT_AWAITING_RESPONSE);
    }
}
