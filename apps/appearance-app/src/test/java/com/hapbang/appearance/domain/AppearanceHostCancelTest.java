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

/**
 * 호스트 일방 취소·모집 전체 취소 규칙 (ISSUE.md APR-32): 위약금 계산(30%, 원 단위 미만 버림), 취소 조건,
 * 처리 중 취소 요청 확인, 모집글 빈자리 금액, 위약금 정산 작업.
 */
class AppearanceHostCancelTest {

    private static final Long HOST = 1L;
    private static final Long GUEST = 2L;
    private static final Long OTHER = 3L;
    private static final LocalDateTime SENT_AT = LocalDateTime.of(2026, 10, 13, 12, 0);
    private static final LocalDateTime NOW = SENT_AT.plusDays(1);
    private static final AppearancePolicy POLICY = new AppearancePolicy(20_000, 30, Duration.ofDays(7), 2, 10);

    /** 요청서에서 성립한 출연. 방송 예정 일시 = SENT_AT + 7일 */
    private static Appearance established(long fee) {
        AppearanceRequest request = AppearanceRequest.create(HOST, GUEST,
                AppearanceRequestFixture.content(SENT_AT, fee), POLICY, SENT_AT);
        request.markRequested();
        ReflectionTestUtils.setField(request, "id", 12L);
        ReflectionTestUtils.setField(request, "version", 0L);
        request.accept(GUEST, 0L, NOW);
        Appearance appearance = Appearance.establishFrom(request, NOW);
        ReflectionTestUtils.setField(appearance, "id", 30L);
        return appearance;
    }

    private static void assertErrorCode(Runnable action, AppearanceErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(AppearanceException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }

    @Nested
    @DisplayName("출연 일방 취소")
    class HostCancel {

        @Test
        @DisplayName("성립한 출연: CANCELED_BY_HOST, 위약금 30% (30,000 → 9,000), 사유 다듬음, 취소 시각")
        void cancel() {
            Appearance appearance = established(30_000);

            long penalty = appearance.cancelByHost(HOST, false, "  일정이 취소됐습니다.  ", POLICY, NOW);

            assertThat(penalty).isEqualTo(9_000);
            assertThat(appearance.getStatus()).isEqualTo(AppearanceStatus.CANCELED_BY_HOST);
            assertThat(appearance.getPenaltyAmount()).isEqualTo(9_000);
            assertThat(appearance.getHostCancelReason()).isEqualTo("일정이 취소됐습니다.");
            assertThat(appearance.getCanceledAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("위약금은 원 단위 미만 버림 (33,333 × 30% = 9,999.9 → 9,999), 사유는 선택(공백이면 null)")
        void floorAndOptionalReason() {
            Appearance appearance = established(33_333);

            assertThat(appearance.cancelByHost(HOST, false, "   ", POLICY, NOW)).isEqualTo(9_999);
            assertThat(appearance.getHostCancelReason()).isNull();
        }

        @Test
        @DisplayName("게스트·다른 회원 NOT_APPEARANCE_HOST, 방송 예정 일시 지남 APPEARANCE_SCHEDULE_PASSED, 사유 501자 INVALID_REQUEST")
        void rejected() {
            Appearance appearance = established(30_000);
            assertErrorCode(() -> appearance.cancelByHost(GUEST, false, null, POLICY, NOW),
                    AppearanceErrorCode.NOT_APPEARANCE_HOST);
            assertErrorCode(() -> appearance.cancelByHost(OTHER, false, null, POLICY, NOW),
                    AppearanceErrorCode.NOT_APPEARANCE_HOST);
            assertErrorCode(() -> appearance.cancelByHost(HOST, false, null, POLICY, appearance.getScheduledAt()),
                    AppearanceErrorCode.APPEARANCE_SCHEDULE_PASSED);
            assertErrorCode(() -> appearance.cancelByHost(HOST, false, "가".repeat(501), POLICY, NOW),
                    AppearanceErrorCode.INVALID_REQUEST);
            assertThat(appearance.getStatus()).isEqualTo(AppearanceStatus.ESTABLISHED);
        }

        @Test
        @DisplayName("합의 취소·이미 일방 취소된 출연은 APPEARANCE_NOT_CANCELABLE")
        void notCancelable() {
            Appearance agreed = established(30_000);
            AppearanceCancelRequest.request(agreed, GUEST, "사유", 0, POLICY, NOW);
            agreed.agreeCancel(NOW);
            assertErrorCode(() -> agreed.cancelByHost(HOST, true, null, POLICY, NOW),
                    AppearanceErrorCode.APPEARANCE_NOT_CANCELABLE);

            Appearance canceled = established(30_000);
            canceled.cancelByHost(HOST, false, null, POLICY, NOW);
            assertErrorCode(() -> canceled.cancelByHost(HOST, false, null, POLICY, NOW),
                    AppearanceErrorCode.APPEARANCE_NOT_CANCELABLE);
        }

        @Test
        @DisplayName("처리 중인 취소 요청: 확인 없으면 CANCEL_REQUEST_CONFIRM_REQUIRED(상태 그대로), 확인하면 취소. 요청은 CLOSED_BY_HOST_CANCEL로 종료")
        void pendingCancelRequest() {
            Appearance appearance = established(30_000);
            AppearanceCancelRequest pending = AppearanceCancelRequest.request(appearance, GUEST, "사유", 0, POLICY, NOW);
            assertThat(appearance.hasPendingCancelRequest()).isTrue();

            assertErrorCode(() -> appearance.cancelByHost(HOST, false, null, POLICY, NOW),
                    AppearanceErrorCode.CANCEL_REQUEST_CONFIRM_REQUIRED);
            assertThat(appearance.getStatus()).isEqualTo(AppearanceStatus.CANCEL_REQUESTED);

            appearance.cancelByHost(HOST, true, null, POLICY, NOW);
            pending.closeByHostCancel(NOW);

            assertThat(appearance.getStatus()).isEqualTo(AppearanceStatus.CANCELED_BY_HOST);
            assertThat(pending.getStatus()).isEqualTo(AppearanceCancelRequestStatus.CLOSED_BY_HOST_CANCEL);
            assertThat(pending.getRespondedAt()).isEqualTo(NOW);
        }
    }

    @Nested
    @DisplayName("모집 전체 취소")
    class RecruitCancel {

        private AppearanceRecruit newRecruiting() {
            AppearanceRecruit recruit = AppearanceRecruit.create(HOST, AppearanceRecruitFixture.content(NOW), POLICY,
                    NOW);
            recruit.markRecruiting();
            return recruit;
        }

        @Test
        @DisplayName("모집 중(3명 중 1명 성립): CANCELED, 빈자리 금액 2 × 30,000 = 60,000, 성립 인원은 그대로, 사유·취소 시각")
        void recruiting() {
            AppearanceRecruit recruit = newRecruiting();
            recruit.increaseSelected();

            long vacancy = recruit.cancel(HOST, "  방송 취소  ", NOW);

            assertThat(vacancy).isEqualTo(60_000);
            assertThat(recruit.getStatus()).isEqualTo(AppearanceRecruitStatus.CANCELED);
            assertThat(recruit.getSelectedCount()).isEqualTo(1);
            assertThat(recruit.getCancelReason()).isEqualTo("방송 취소");
            assertThat(recruit.getCanceledAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("마감 뒤 취소: 빈자리는 마감 때 이미 반환해서 0")
        void closed() {
            AppearanceRecruit recruit = newRecruiting();
            recruit.closeEarly(HOST, NOW);

            assertThat(recruit.cancel(HOST, null, NOW)).isZero();
            assertThat(recruit.getStatus()).isEqualTo(AppearanceRecruitStatus.CANCELED);
        }

        @Test
        @DisplayName("다른 회원 NOT_RECRUIT_HOST, 보류 처리 중·이미 취소 APPEARANCE_RECRUIT_NOT_CANCELABLE, 방송 예정 일시 지남 APPEARANCE_SCHEDULE_PASSED")
        void rejected() {
            AppearanceRecruit recruit = newRecruiting();
            assertErrorCode(() -> recruit.cancel(OTHER, null, NOW), AppearanceErrorCode.NOT_RECRUIT_HOST);
            assertErrorCode(() -> recruit.cancel(HOST, null, recruit.getScheduledAt()),
                    AppearanceErrorCode.APPEARANCE_SCHEDULE_PASSED);

            AppearanceRecruit pending = AppearanceRecruit.create(HOST, AppearanceRecruitFixture.content(NOW), POLICY,
                    NOW);
            assertErrorCode(() -> pending.cancel(HOST, null, NOW), AppearanceErrorCode.APPEARANCE_RECRUIT_NOT_CANCELABLE);

            recruit.cancel(HOST, null, NOW);
            assertErrorCode(() -> recruit.cancel(HOST, null, NOW), AppearanceErrorCode.APPEARANCE_RECRUIT_NOT_CANCELABLE);
        }
    }

    @Test
    @DisplayName("위약금 정산 작업: PENALTY, 기준액 = 출연료, 위약금은 받는 회원에게, 호스트 반환 몫 = 기준액 − 위약금. 위약금 0·기준액 초과는 IllegalArgumentException")
    void penaltyTask() {
        HpTask task = HpTask.penalty(HpTaskRefType.APPEARANCE_REQUEST, 12L, HOST, 30_000, GUEST, 9_000, 30L,
                HpTaskReason.HOST_CANCELED, "APPEARANCE_REQUEST-12-PENALTY-APPEARANCE-30", NOW);

        assertThat(task.getTaskType()).isEqualTo(HpTaskType.PENALTY);
        assertThat(task.getMemberId()).isEqualTo(HOST);
        assertThat(task.getReceiverId()).isEqualTo(GUEST);
        assertThat(task.getAmount()).isEqualTo(30_000);
        assertThat(task.getPayoutAmount()).isEqualTo(9_000);
        assertThat(task.releaseAmountOfPenalty()).isEqualTo(21_000);
        assertThat(task.getAppearanceId()).isEqualTo(30L);
        assertThatThrownBy(() -> HpTask.penalty(HpTaskRefType.APPEARANCE_REQUEST, 12L, HOST, 30_000, GUEST, 0, 30L,
                HpTaskReason.HOST_CANCELED, "k", NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HpTask.penalty(HpTaskRefType.APPEARANCE_REQUEST, 12L, HOST, 30_000, GUEST, 30_001,
                30L, HpTaskReason.HOST_CANCELED, "k", NOW)).isInstanceOf(IllegalArgumentException.class);
    }
}
