package com.hapbang.appearance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.appearance.fixture.AppearanceRecruitFixture;
import com.hapbang.appearance.fixture.AppearanceRequestFixture;
import com.hapbang.shared.appearance.dto.AppearanceCancelRequestDto;

/**
 * 출연 취소 요청 규칙: 요청(게스트·성립 상태·방송 예정 일시 전·횟수·사유), 응답(호스트·처리 중·방송 예정 일시 전),
 * 수락(합의 취소)·거절·자동 거절에 따른 출연 상태. 모집글 성립 인원 감소.
 */
class AppearanceCancelRequestTest {

    private static final Long HOST = 1L;
    private static final Long GUEST = 2L;
    private static final Long OTHER = 3L;
    private static final LocalDateTime SENT_AT = LocalDateTime.of(2026, 10, 13, 12, 0);
    private static final LocalDateTime NOW = SENT_AT.plusDays(1);
    private static final AppearancePolicy POLICY = new AppearancePolicy(20_000, 30, Duration.ofDays(7), 2, 10);

    /** 요청서에서 성립한 출연. 방송 예정 일시 = SENT_AT + 7일 */
    private static Appearance established() {
        AppearanceRequest request = AppearanceRequest.create(HOST, GUEST, AppearanceRequestFixture.content(SENT_AT),
                POLICY, SENT_AT);
        request.markRequested();
        ReflectionTestUtils.setField(request, "id", 12L);
        ReflectionTestUtils.setField(request, "version", 0L);
        request.accept(GUEST, 0L, NOW);
        Appearance appearance = Appearance.establishFrom(request, NOW);
        ReflectionTestUtils.setField(appearance, "id", 30L);
        return appearance;
    }

    private static AppearanceCancelRequest newRequest(Appearance appearance, int previousCount) {
        return AppearanceCancelRequest.request(appearance, GUEST, "일정이 생겼습니다.", previousCount, POLICY, NOW);
    }

    private static void assertErrorCode(Runnable action, AppearanceErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(AppearanceException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }

    @Nested
    @DisplayName("취소 요청")
    class Request {

        @Test
        @DisplayName("게스트가 성립한 출연에 사유와 함께 요청하면 REQUESTED(순번 1), 출연은 CANCEL_REQUESTED. 사유 앞뒤 공백은 지운다")
        void request() {
            Appearance appearance = established();

            AppearanceCancelRequest cancelRequest = AppearanceCancelRequest.request(appearance, GUEST,
                    "  일정이 생겼습니다.  ", 0, POLICY, NOW);

            assertThat(cancelRequest.getStatus()).isEqualTo(AppearanceCancelRequestStatus.REQUESTED);
            assertThat(cancelRequest.getSequence()).isEqualTo(1);
            assertThat(cancelRequest.getReason()).isEqualTo("일정이 생겼습니다.");
            assertThat(cancelRequest.getAppearanceId()).isEqualTo(30L);
            assertThat(cancelRequest.getHostId()).isEqualTo(HOST);
            assertThat(cancelRequest.getGuestId()).isEqualTo(GUEST);
            assertThat(appearance.getStatus()).isEqualTo(AppearanceStatus.CANCEL_REQUESTED);
        }

        @Test
        @DisplayName("사유 없음·공백 / 501자 → INVALID_REQUEST (500자는 허용), 출연 상태는 그대로")
        void reason() {
            Appearance appearance = established();
            assertErrorCode(() -> AppearanceCancelRequest.request(appearance, GUEST, null, 0, POLICY, NOW),
                    AppearanceErrorCode.INVALID_REQUEST);
            assertErrorCode(() -> AppearanceCancelRequest.request(appearance, GUEST, "  ", 0, POLICY, NOW),
                    AppearanceErrorCode.INVALID_REQUEST);
            assertErrorCode(() -> AppearanceCancelRequest.request(appearance, GUEST, "가".repeat(501), 0, POLICY, NOW),
                    AppearanceErrorCode.INVALID_REQUEST);
            assertThat(appearance.getStatus()).isEqualTo(AppearanceStatus.ESTABLISHED);

            assertThat(AppearanceCancelRequest.request(appearance, GUEST, "가".repeat(500), 0, POLICY, NOW)
                    .getReason()).hasSize(500);
        }

        @Test
        @DisplayName("호스트·다른 회원 NOT_APPEARANCE_GUEST, 처리 중이면 CANCEL_REQUEST_ALREADY_PENDING")
        void notGuestOrPending() {
            Appearance appearance = established();
            assertErrorCode(() -> AppearanceCancelRequest.request(appearance, HOST, "사유", 0, POLICY, NOW),
                    AppearanceErrorCode.NOT_APPEARANCE_GUEST);
            assertErrorCode(() -> AppearanceCancelRequest.request(appearance, OTHER, "사유", 0, POLICY, NOW),
                    AppearanceErrorCode.NOT_APPEARANCE_GUEST);

            newRequest(appearance, 0);
            assertErrorCode(() -> newRequest(appearance, 1), AppearanceErrorCode.CANCEL_REQUEST_ALREADY_PENDING);
        }

        @Test
        @DisplayName("방송 예정 일시와 같거나 지나면 APPEARANCE_SCHEDULE_PASSED (1초 전은 허용)")
        void schedulePassed() {
            Appearance appearance = established();
            LocalDateTime scheduledAt = appearance.getScheduledAt();

            assertErrorCode(() -> AppearanceCancelRequest.request(appearance, GUEST, "사유", 0, POLICY, scheduledAt),
                    AppearanceErrorCode.APPEARANCE_SCHEDULE_PASSED);
            AppearanceCancelRequest.request(appearance, GUEST, "사유", 0, POLICY, scheduledAt.minusSeconds(1));
            assertThat(appearance.getStatus()).isEqualTo(AppearanceStatus.CANCEL_REQUESTED);
        }

        @Test
        @DisplayName("거절된 뒤 1번 더(순번 2) 요청할 수 있고, 이미 2번 보냈으면 CANCEL_REQUEST_LIMIT_EXCEEDED")
        void limit() {
            Appearance appearance = established();
            newRequest(appearance, 0).reject(null, NOW);
            appearance.keepAfterCancelRejected();

            assertThat(newRequest(appearance, 1).getSequence()).isEqualTo(2);
            appearance.keepAfterCancelRejected();
            assertThatThrownBy(() -> newRequest(appearance, 2))
                    .isInstanceOf(AppearanceException.class)
                    .hasMessage("출연 취소 요청은 출연 1건당 2번까지 할 수 있습니다.")
                    .extracting("errorCode").isEqualTo(AppearanceErrorCode.CANCEL_REQUEST_LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("합의 취소된 출연에는 APPEARANCE_NOT_CANCELABLE")
        void notCancelable() {
            Appearance appearance = established();
            newRequest(appearance, 0).accept(NOW);
            appearance.agreeCancel(NOW);

            assertErrorCode(() -> newRequest(appearance, 1), AppearanceErrorCode.APPEARANCE_NOT_CANCELABLE);
        }
    }

    @Nested
    @DisplayName("호스트 응답·자동 거절")
    class Respond {

        @Test
        @DisplayName("수락: 요청 ACCEPTED + 응답 시각, 출연 CANCELED_BY_AGREEMENT + 취소 시각")
        void accept() {
            Appearance appearance = established();
            AppearanceCancelRequest cancelRequest = newRequest(appearance, 0);

            appearance.validateCancelRespondable(HOST, NOW);
            cancelRequest.accept(NOW);
            appearance.agreeCancel(NOW);

            assertThat(cancelRequest.getStatus()).isEqualTo(AppearanceCancelRequestStatus.ACCEPTED);
            assertThat(cancelRequest.getRespondedAt()).isEqualTo(NOW);
            assertThat(appearance.getStatus()).isEqualTo(AppearanceStatus.CANCELED_BY_AGREEMENT);
            assertThat(appearance.getCanceledAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("거절: REJECTED + 거절 사유(선택, 공백이면 비움), 출연 ESTABLISHED로 돌아옴. 거절 사유 501자는 INVALID_REQUEST")
        void reject() {
            Appearance appearance = established();
            AppearanceCancelRequest cancelRequest = newRequest(appearance, 0);
            assertErrorCode(() -> cancelRequest.reject("가".repeat(501), NOW), AppearanceErrorCode.INVALID_REQUEST);

            cancelRequest.reject("  이미 홍보 중입니다.  ", NOW);
            appearance.keepAfterCancelRejected();

            assertThat(cancelRequest.getStatus()).isEqualTo(AppearanceCancelRequestStatus.REJECTED);
            assertThat(cancelRequest.getRejectReason()).isEqualTo("이미 홍보 중입니다.");
            assertThat(appearance.getStatus()).isEqualTo(AppearanceStatus.ESTABLISHED);

            AppearanceCancelRequest blank = newRequest(established(), 0);
            blank.reject("   ", NOW);
            assertThat(blank.getRejectReason()).isNull();
        }

        @Test
        @DisplayName("자동 거절: AUTO_REJECTED, 거절 사유 없음. 대상은 처리 중이고 방송 예정 일시와 같거나 지난 출연")
        void autoReject() {
            Appearance appearance = established();
            AppearanceCancelRequest cancelRequest = newRequest(appearance, 0);
            LocalDateTime scheduledAt = appearance.getScheduledAt();
            assertThat(appearance.isCancelRequestDue(scheduledAt.minusSeconds(1))).isFalse();
            assertThat(appearance.isCancelRequestDue(scheduledAt)).isTrue();

            cancelRequest.autoReject(scheduledAt);
            appearance.keepAfterCancelRejected();

            assertThat(cancelRequest.getStatus()).isEqualTo(AppearanceCancelRequestStatus.AUTO_REJECTED);
            assertThat(cancelRequest.getRejectReason()).isNull();
            assertThat(appearance.isCancelRequestDue(scheduledAt)).isFalse();
        }

        @Test
        @DisplayName("응답 가능 확인: 게스트·다른 회원 NOT_APPEARANCE_HOST, 처리 중 아님 CANCEL_REQUEST_NOT_PENDING, 방송 예정 일시 지남 APPEARANCE_SCHEDULE_PASSED")
        void respondable() {
            Appearance appearance = established();
            assertErrorCode(() -> appearance.validateCancelRespondable(HOST, NOW),
                    AppearanceErrorCode.CANCEL_REQUEST_NOT_PENDING);

            newRequest(appearance, 0);
            assertErrorCode(() -> appearance.validateCancelRespondable(GUEST, NOW),
                    AppearanceErrorCode.NOT_APPEARANCE_HOST);
            assertErrorCode(() -> appearance.validateCancelRespondable(HOST, appearance.getScheduledAt()),
                    AppearanceErrorCode.APPEARANCE_SCHEDULE_PASSED);
            appearance.validateCancelRespondable(HOST, NOW);
        }

        @Test
        @DisplayName("이미 응답한 요청에 다시 응답하면 CANCEL_REQUEST_NOT_PENDING")
        void notPending() {
            AppearanceCancelRequest cancelRequest = newRequest(established(), 0);
            cancelRequest.accept(NOW);

            assertErrorCode(() -> cancelRequest.reject(null, NOW), AppearanceErrorCode.CANCEL_REQUEST_NOT_PENDING);
            assertErrorCode(() -> cancelRequest.autoReject(NOW), AppearanceErrorCode.CANCEL_REQUEST_NOT_PENDING);
        }
    }

    @Test
    @DisplayName("다른 모듈에 넘기는 정보: 취소 요청 ID·출연 ID·제목·호스트·게스트·순번·방송 예정 일시 (사유는 담지 않음)")
    void toDto() {
        Appearance appearance = established();
        AppearanceCancelRequest cancelRequest = newRequest(appearance, 0);

        AppearanceCancelRequestDto dto = cancelRequest.toDto("주말 합방", appearance.getScheduledAt());

        assertThat(dto.appearanceId()).isEqualTo(30L);
        assertThat(dto.title()).isEqualTo("주말 합방");
        assertThat(dto.hostId()).isEqualTo(HOST);
        assertThat(dto.guestId()).isEqualTo(GUEST);
        assertThat(dto.sequence()).isEqualTo(1);
        assertThat(dto.scheduledAt()).isEqualTo(appearance.getScheduledAt());
    }

    @Test
    @DisplayName("출연은 호스트·게스트만 볼 수 있다 (다른 회원 APPEARANCE_NOT_FOUND)")
    void viewable() {
        Appearance appearance = established();
        appearance.validateViewableBy(HOST);
        appearance.validateViewableBy(GUEST);
        assertErrorCode(() -> appearance.validateViewableBy(OTHER), AppearanceErrorCode.APPEARANCE_NOT_FOUND);
    }

    @Test
    @DisplayName("모집글 성립 인원 감소: 1명 → 0명, 0명에서 줄이면 IllegalStateException. 마감 여부는 isClosed로 본다")
    void decreaseSelected() {
        AppearanceRecruit recruit = AppearanceRecruit.create(HOST, AppearanceRecruitFixture.content(NOW), POLICY, NOW);
        recruit.markRecruiting();
        recruit.increaseSelected();

        recruit.decreaseSelected();

        assertThat(recruit.getSelectedCount()).isZero();
        assertThat(recruit.isClosed()).isFalse();
        assertThatThrownBy(recruit::decreaseSelected).isInstanceOf(IllegalStateException.class);
        recruit.closeEarly(HOST, NOW);
        assertThat(recruit.isClosed()).isTrue();
    }

    @Test
    @DisplayName("출연별 반환 요청 ID는 마감 반환(…-RELEASE-1)과 겹치지 않는다")
    void appearanceRequestId() {
        assertThat(HpTask.appearanceRequestId(HpTaskRefType.APPEARANCE_RECRUIT, 5L, HpTaskType.RELEASE, 12L))
                .isEqualTo("APPEARANCE_RECRUIT-5-RELEASE-APPEARANCE-12")
                .isNotEqualTo(HpTask.requestId(HpTaskRefType.APPEARANCE_RECRUIT, 5L, HpTaskType.RELEASE, 1));
    }
}
