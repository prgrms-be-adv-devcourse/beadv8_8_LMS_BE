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
 * 출연 확인 규칙 (ISSUE.md APR-35): 응답 기간(방송 예정 일시 ~ D+7 전), 확인·불참, 번복 불가, 자동 확인 대상, 지급 작업.
 */
class AppearanceConfirmTest {

    private static final Long HOST = 1L;
    private static final Long GUEST = 2L;
    private static final Long OTHER = 3L;
    private static final LocalDateTime SENT_AT = LocalDateTime.of(2026, 10, 13, 12, 0);
    private static final LocalDateTime ESTABLISHED_AT = SENT_AT.plusDays(1);
    /** 방송 예정 일시 (요청서 픽스처: 보낸 시각 + 7일) */
    private static final LocalDateTime SCHEDULED = SENT_AT.plusDays(7);
    /** 확인 기간이 끝나는 시각 (방송 예정 일시 + 7일) */
    private static final LocalDateTime DEADLINE = SCHEDULED.plusDays(7);
    private static final AppearancePolicy POLICY = new AppearancePolicy(20_000, 30, Duration.ofDays(7), 2, 10);

    /** 요청서에서 성립한 출연 (출연료 30,000) */
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

    private static void assertError(Runnable action, AppearanceErrorCode errorCode, String message) {
        assertThatThrownBy(action::run)
                .isInstanceOf(AppearanceException.class)
                .hasMessage(message)
                .extracting("errorCode").isEqualTo(errorCode);
    }

    @Nested
    @DisplayName("호스트 확인")
    class Confirm {

        @Test
        @DisplayName("방송 예정 일시 그 시각부터 확인할 수 있다: CONFIRMED, 확인 방식 HOST, 확인 시각")
        void atScheduled() {
            Appearance appearance = established();

            appearance.confirmByHost(HOST, POLICY, SCHEDULED);

            assertThat(appearance.getStatus()).isEqualTo(AppearanceStatus.CONFIRMED);
            assertThat(appearance.getConfirmType()).isEqualTo(AppearanceConfirmType.HOST);
            assertThat(appearance.getConfirmedAt()).isEqualTo(SCHEDULED);
            assertThat(appearance.getAbsentAt()).isNull();
        }

        @Test
        @DisplayName("확인 기간이 끝나기 1초 전까지는 확인할 수 있다")
        void justBeforeDeadline() {
            Appearance appearance = established();

            appearance.confirmByHost(HOST, POLICY, DEADLINE.minusSeconds(1));

            assertThat(appearance.getStatus()).isEqualTo(AppearanceStatus.CONFIRMED);
        }

        @Test
        @DisplayName("방송 예정 일시 1초 전 APPEARANCE_CONFIRM_NOT_OPEN, 확인 기간이 끝난 시각 APPEARANCE_CONFIRM_PERIOD_ENDED")
        void outOfPeriod() {
            Appearance appearance = established();

            assertError(() -> appearance.confirmByHost(HOST, POLICY, SCHEDULED.minusSeconds(1)),
                    AppearanceErrorCode.APPEARANCE_CONFIRM_NOT_OPEN, "방송 예정 일시가 지난 뒤에 출연 확인을 할 수 있습니다.");
            assertError(() -> appearance.confirmByHost(HOST, POLICY, DEADLINE),
                    AppearanceErrorCode.APPEARANCE_CONFIRM_PERIOD_ENDED, "출연 확인 기간이 지났습니다. 출연은 자동으로 확인됩니다.");
            assertThat(appearance.getStatus()).isEqualTo(AppearanceStatus.ESTABLISHED);
        }

        @Test
        @DisplayName("게스트·다른 회원은 NOT_APPEARANCE_HOST (\"출연의 호스트만 출연을 확인할 수 있습니다.\")")
        void notHost() {
            Appearance appearance = established();

            assertError(() -> appearance.confirmByHost(GUEST, POLICY, SCHEDULED),
                    AppearanceErrorCode.NOT_APPEARANCE_HOST, "출연의 호스트만 출연을 확인할 수 있습니다.");
            assertError(() -> appearance.markAbsent(OTHER, null, POLICY, SCHEDULED),
                    AppearanceErrorCode.NOT_APPEARANCE_HOST, "출연의 호스트만 출연을 확인할 수 있습니다.");
        }
    }

    @Nested
    @DisplayName("불참")
    class Absent {

        @Test
        @DisplayName("ABSENT, 불참 시각, 사유는 앞뒤 공백을 다듬는다")
        void absent() {
            Appearance appearance = established();

            appearance.markAbsent(HOST, "  연락이 되지 않았습니다.  ", POLICY, SCHEDULED.plusHours(2));

            assertThat(appearance.getStatus()).isEqualTo(AppearanceStatus.ABSENT);
            assertThat(appearance.getAbsentAt()).isEqualTo(SCHEDULED.plusHours(2));
            assertThat(appearance.getAbsentReason()).isEqualTo("연락이 되지 않았습니다.");
            assertThat(appearance.getConfirmType()).isNull();
            assertThat(appearance.getConfirmedAt()).isNull();
        }

        @Test
        @DisplayName("사유는 선택: 없거나 공백뿐이면 null, 501자는 INVALID_REQUEST(\"불참 사유는 500자 이하여야 합니다.\")이고 상태는 그대로")
        void reason() {
            Appearance blank = established();
            blank.markAbsent(HOST, "   ", POLICY, SCHEDULED);
            assertThat(blank.getAbsentReason()).isNull();

            Appearance tooLong = established();
            assertError(() -> tooLong.markAbsent(HOST, "가".repeat(501), POLICY, SCHEDULED),
                    AppearanceErrorCode.INVALID_REQUEST, "불참 사유는 500자 이하여야 합니다.");
            assertThat(tooLong.getStatus()).isEqualTo(AppearanceStatus.ESTABLISHED);
        }

        @Test
        @DisplayName("응답 기간은 확인과 같다 (방송 예정 일시 전 NOT_OPEN, 기간 끝 PERIOD_ENDED)")
        void period() {
            Appearance appearance = established();

            assertError(() -> appearance.markAbsent(HOST, null, POLICY, SCHEDULED.minusSeconds(1)),
                    AppearanceErrorCode.APPEARANCE_CONFIRM_NOT_OPEN, "방송 예정 일시가 지난 뒤에 출연 확인을 할 수 있습니다.");
            assertError(() -> appearance.markAbsent(HOST, null, POLICY, DEADLINE),
                    AppearanceErrorCode.APPEARANCE_CONFIRM_PERIOD_ENDED, "출연 확인 기간이 지났습니다. 출연은 자동으로 확인됩니다.");
        }
    }

    @Nested
    @DisplayName("응답할 수 없는 상태 (APPEARANCE_NOT_CONFIRMABLE)")
    class NotConfirmable {

        @Test
        @DisplayName("이미 확인·불참한 출연은 다시 응답할 수 없다 (번복 불가)")
        void alreadyResponded() {
            Appearance confirmed = established();
            confirmed.confirmByHost(HOST, POLICY, SCHEDULED);
            Appearance absent = established();
            absent.markAbsent(HOST, null, POLICY, SCHEDULED);

            String message = "이미 출연 확인에 응답한 출연입니다.";
            assertError(() -> confirmed.markAbsent(HOST, null, POLICY, SCHEDULED.plusHours(1)),
                    AppearanceErrorCode.APPEARANCE_NOT_CONFIRMABLE, message);
            assertError(() -> confirmed.confirmByHost(HOST, POLICY, SCHEDULED.plusHours(1)),
                    AppearanceErrorCode.APPEARANCE_NOT_CONFIRMABLE, message);
            assertError(() -> absent.confirmByHost(HOST, POLICY, SCHEDULED.plusHours(1)),
                    AppearanceErrorCode.APPEARANCE_NOT_CONFIRMABLE, message);
            assertThat(confirmed.getStatus()).isEqualTo(AppearanceStatus.CONFIRMED);
            assertThat(absent.getStatus()).isEqualTo(AppearanceStatus.ABSENT);
        }

        @Test
        @DisplayName("출연 취소 요청 처리 중이면 자동 거절로 정리된 뒤에 응답할 수 있다")
        void cancelRequested() {
            Appearance appearance = established();
            appearance.requestCancel(GUEST, 0, 2, ESTABLISHED_AT);

            assertError(() -> appearance.confirmByHost(HOST, POLICY, SCHEDULED),
                    AppearanceErrorCode.APPEARANCE_NOT_CONFIRMABLE,
                    "처리 중인 출연 취소 요청이 정리된 뒤 출연을 확인할 수 있습니다.");

            appearance.keepAfterCancelRejected();
            appearance.confirmByHost(HOST, POLICY, SCHEDULED);
            assertThat(appearance.getStatus()).isEqualTo(AppearanceStatus.CONFIRMED);
        }

        @Test
        @DisplayName("호스트 일방 취소·합의 취소된 출연은 \"취소된 출연입니다.\"")
        void canceled() {
            Appearance hostCanceled = established();
            hostCanceled.cancelByHost(HOST, false, null, POLICY, ESTABLISHED_AT);
            Appearance agreed = established();
            agreed.requestCancel(GUEST, 0, 2, ESTABLISHED_AT);
            agreed.agreeCancel(ESTABLISHED_AT);

            assertError(() -> hostCanceled.confirmByHost(HOST, POLICY, SCHEDULED),
                    AppearanceErrorCode.APPEARANCE_NOT_CONFIRMABLE, "취소된 출연입니다.");
            assertError(() -> agreed.markAbsent(HOST, null, POLICY, SCHEDULED),
                    AppearanceErrorCode.APPEARANCE_NOT_CONFIRMABLE, "취소된 출연입니다.");
        }
    }

    @Nested
    @DisplayName("자동 확인")
    class AutoConfirm {

        @Test
        @DisplayName("확인 기간이 끝난 시각부터 대상: 1초 전 false, 그 시각 true → CONFIRMED, 확인 방식 AUTO")
        void due() {
            Appearance appearance = established();

            assertThat(appearance.isAutoConfirmDue(POLICY, DEADLINE.minusSeconds(1))).isFalse();
            assertThat(appearance.isAutoConfirmDue(POLICY, DEADLINE)).isTrue();

            appearance.confirmAutomatically(POLICY, DEADLINE);
            assertThat(appearance.getStatus()).isEqualTo(AppearanceStatus.CONFIRMED);
            assertThat(appearance.getConfirmType()).isEqualTo(AppearanceConfirmType.AUTO);
            assertThat(appearance.getConfirmedAt()).isEqualTo(DEADLINE);
        }

        @Test
        @DisplayName("성립 상태가 아니면 대상이 아니다 (불참·취소 요청 중), 대상이 아닌데 부르면 IllegalStateException")
        void notDue() {
            Appearance absent = established();
            absent.markAbsent(HOST, null, POLICY, SCHEDULED);
            Appearance cancelRequested = established();
            cancelRequested.requestCancel(GUEST, 0, 2, ESTABLISHED_AT);

            assertThat(absent.isAutoConfirmDue(POLICY, DEADLINE)).isFalse();
            assertThat(cancelRequested.isAutoConfirmDue(POLICY, DEADLINE)).isFalse();
            assertThatThrownBy(() -> absent.confirmAutomatically(POLICY, DEADLINE))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> established().confirmAutomatically(POLICY, DEADLINE.minusSeconds(1)))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("지급 작업과 보류 대상")
    class PayoutTask {

        @Test
        @DisplayName("지급 작업: PAYOUT, 받는 회원 게스트, 지급액 = 출연료 전부, 출연 ID, PENDING")
        void payout() {
            HpTask task = HpTask.payout(HpTaskRefType.APPEARANCE_REQUEST, 12L, HOST, 30_000, GUEST, 30L,
                    HpTaskReason.APPEARANCE_CONFIRMED, "APPEARANCE_REQUEST-12-PAYOUT-APPEARANCE-30", SCHEDULED);

            assertThat(task.getTaskType()).isEqualTo(HpTaskType.PAYOUT);
            assertThat(task.getMemberId()).isEqualTo(HOST);
            assertThat(task.getReceiverId()).isEqualTo(GUEST);
            assertThat(task.getAmount()).isEqualTo(30_000);
            assertThat(task.getPayoutAmount()).isEqualTo(30_000);
            assertThat(task.getAppearanceId()).isEqualTo(30L);
            assertThat(task.getStatus()).isEqualTo(HpTaskStatus.PENDING);
            assertThatThrownBy(() -> HpTask.payout(HpTaskRefType.APPEARANCE_REQUEST, 12L, HOST, 0, GUEST, 30L,
                    HpTaskReason.APPEARANCE_CONFIRMED, "k", SCHEDULED))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("요청서 출처 출연의 보류 대상은 요청서 (APPEARANCE_REQUEST, 요청서 ID)")
        void holdRef() {
            Appearance appearance = established();

            assertThat(appearance.holdRefType()).isEqualTo(HpTaskRefType.APPEARANCE_REQUEST);
            assertThat(appearance.holdRefId()).isEqualTo(12L);
        }
    }
}
