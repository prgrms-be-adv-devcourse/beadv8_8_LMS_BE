package com.hapbang.appearance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.hapbang.appearance.fixture.AppearanceRecruitFixture;

/**
 * 출연 모집 지원 규칙과, 지원·응답에 쓰는 모집글 쪽 확인(모집 중·호스트·정원), 모집 출연 성립.
 */
class AppearanceApplicationTest {

    private static final Long HOST = 1L;
    private static final Long GUEST = 2L;
    private static final Long OTHER = 3L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 12, 0);
    private static final AppearancePolicy POLICY = new AppearancePolicy(20_000, 30, Duration.ofDays(7), 2, 10);

    /** 모집 중인 모집글 (모집 마감 = NOW + 2일, 1인당 30,000 × 정원) */
    private static AppearanceRecruit recruiting(int recruitCount) {
        AppearanceRecruit recruit = AppearanceRecruit.create(HOST,
                AppearanceRecruitFixture.content(NOW, 30_000, recruitCount), POLICY, NOW);
        recruit.markRecruiting();
        return recruit;
    }

    private static AppearanceApplication applied() {
        return AppearanceApplication.apply(recruiting(3), GUEST, "지원합니다.", NOW);
    }

    private static void assertErrorCode(Runnable action, AppearanceErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(AppearanceException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }

    @Nested
    @DisplayName("지원")
    class Apply {

        @Test
        @DisplayName("모집 중이고 마감 전이면 APPLIED로 지원한다 (메시지는 선택)")
        void apply() {
            AppearanceApplication application = applied();

            assertThat(application.getStatus()).isEqualTo(AppearanceApplicationStatus.APPLIED);
            assertThat(application.getGuestId()).isEqualTo(GUEST);
            assertThat(application.getMessage()).isEqualTo("지원합니다.");
            assertThat(AppearanceApplication.apply(recruiting(3), GUEST, null, NOW).getMessage()).isNull();
        }

        @Test
        @DisplayName("메시지 500자는 허용, 501자는 INVALID_REQUEST")
        void messageLength() {
            assertThat(AppearanceApplication.apply(recruiting(3), GUEST, "가".repeat(500), NOW).getMessage())
                    .hasSize(500);
            assertErrorCode(() -> AppearanceApplication.apply(recruiting(3), GUEST, "가".repeat(501), NOW),
                    AppearanceErrorCode.INVALID_REQUEST);
        }

        @Test
        @DisplayName("자기 모집글에는 지원할 수 없다 (CANNOT_APPLY_OWN_RECRUIT)")
        void ownRecruit() {
            assertErrorCode(() -> AppearanceApplication.apply(recruiting(3), HOST, null, NOW),
                    AppearanceErrorCode.CANNOT_APPLY_OWN_RECRUIT);
        }

        @Test
        @DisplayName("모집 중이 아니거나(보류 처리 중) 마감 일시와 같거나 지났으면 APPEARANCE_RECRUIT_NOT_OPEN")
        void notOpen() {
            AppearanceRecruit pending = AppearanceRecruit.create(HOST, AppearanceRecruitFixture.content(NOW), POLICY,
                    NOW);
            assertErrorCode(() -> AppearanceApplication.apply(pending, GUEST, null, NOW),
                    AppearanceErrorCode.APPEARANCE_RECRUIT_NOT_OPEN);

            AppearanceRecruit recruit = recruiting(3);
            assertErrorCode(() -> AppearanceApplication.apply(recruit, GUEST, null, recruit.getRecruitDeadline()),
                    AppearanceErrorCode.APPEARANCE_RECRUIT_NOT_OPEN);
        }

        @Test
        @DisplayName("정원이 찼어도 지원은 받는다 (빈자리가 생길 수 있어서)")
        void fullStillApplicable() {
            AppearanceRecruit recruit = recruiting(1);
            recruit.increaseSelected();

            assertThat(recruit.isFull()).isTrue();
            assertThat(AppearanceApplication.apply(recruit, GUEST, null, NOW).getStatus())
                    .isEqualTo(AppearanceApplicationStatus.APPLIED);
        }
    }

    @Nested
    @DisplayName("지원 취소 (게스트)")
    class Withdraw {

        @Test
        @DisplayName("지원한 게스트가 대기 중에 취소하면 WITHDRAWN + 취소 시각")
        void withdraw() {
            AppearanceApplication application = applied();

            application.withdraw(GUEST, NOW);

            assertThat(application.getStatus()).isEqualTo(AppearanceApplicationStatus.WITHDRAWN);
            assertThat(application.getWithdrawnAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("다른 회원은 NOT_APPLICATION_GUEST, 이미 취소·응답된 지원은 APPLICATION_NOT_PENDING")
        void rejected() {
            AppearanceApplication application = applied();
            assertErrorCode(() -> application.withdraw(OTHER, NOW), AppearanceErrorCode.NOT_APPLICATION_GUEST);

            application.withdraw(GUEST, NOW);
            assertErrorCode(() -> application.withdraw(GUEST, NOW), AppearanceErrorCode.APPLICATION_NOT_PENDING);

            AppearanceApplication selected = applied();
            selected.select(NOW);
            assertErrorCode(() -> selected.withdraw(GUEST, NOW), AppearanceErrorCode.APPLICATION_NOT_PENDING);
        }
    }

    @Nested
    @DisplayName("호스트 응답")
    class Respond {

        @Test
        @DisplayName("수락은 SELECTED, 거절은 NOT_SELECTED + 응답 시각")
        void selectOrReject() {
            AppearanceApplication selected = applied();
            selected.select(NOW);
            assertThat(selected.getStatus()).isEqualTo(AppearanceApplicationStatus.SELECTED);
            assertThat(selected.getRespondedAt()).isEqualTo(NOW);

            AppearanceApplication rejected = applied();
            rejected.reject(NOW);
            assertThat(rejected.getStatus()).isEqualTo(AppearanceApplicationStatus.NOT_SELECTED);
            assertThat(rejected.getRespondedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("대기 중이 아닌 지원(취소·거절됨)에는 응답할 수 없다 (APPLICATION_NOT_PENDING)")
        void notPending() {
            AppearanceApplication withdrawn = applied();
            withdrawn.withdraw(GUEST, NOW);
            assertErrorCode(() -> withdrawn.select(NOW), AppearanceErrorCode.APPLICATION_NOT_PENDING);

            AppearanceApplication rejected = applied();
            rejected.reject(NOW);
            assertErrorCode(() -> rejected.reject(NOW), AppearanceErrorCode.APPLICATION_NOT_PENDING);
        }

        @Test
        @DisplayName("모집글 쪽: 호스트가 아니면 NOT_RECRUIT_HOST, 마감 일시가 지났으면 APPEARANCE_RECRUIT_NOT_OPEN")
        void respondable() {
            AppearanceRecruit recruit = recruiting(3);

            recruit.validateRespondable(HOST, NOW);
            assertThatThrownBy(() -> recruit.validateRespondable(OTHER, NOW))
                    .isInstanceOf(AppearanceException.class)
                    .hasMessage("출연 모집글을 올린 호스트만 지원에 응답할 수 있습니다.")
                    .extracting("errorCode").isEqualTo(AppearanceErrorCode.NOT_RECRUIT_HOST);
            assertErrorCode(() -> recruit.validateRespondable(HOST, recruit.getRecruitDeadline()),
                    AppearanceErrorCode.APPEARANCE_RECRUIT_NOT_OPEN);
        }

        @Test
        @DisplayName("모집글 쪽: 성립 인원은 정원까지만 늘고, 찼으면 RECRUIT_FULL (모집 상태는 그대로)")
        void increaseSelected() {
            AppearanceRecruit recruit = recruiting(2);

            recruit.increaseSelected();
            recruit.increaseSelected();

            assertThat(recruit.getSelectedCount()).isEqualTo(2);
            assertThat(recruit.isFull()).isTrue();
            assertErrorCode(recruit::increaseSelected, AppearanceErrorCode.RECRUIT_FULL);
            assertThat(recruit.getStatus()).isEqualTo(AppearanceRecruitStatus.RECRUITING);
        }
    }

    @Nested
    @DisplayName("모집 출연 성립")
    class Establish {

        @Test
        @DisplayName("수락된 지원으로 출연을 만든다: 출처 RECRUIT, 출연료는 1인당 출연료, 방송 예정 일시 스냅샷")
        void establish() {
            AppearanceRecruit recruit = recruiting(3);
            AppearanceApplication application = AppearanceApplication.apply(recruit, GUEST, null, NOW);
            application.select(NOW);

            Appearance appearance = Appearance.establishFrom(recruit, application, NOW);

            assertThat(appearance.getSourceType()).isEqualTo(AppearanceSourceType.RECRUIT);
            assertThat(appearance.getHostId()).isEqualTo(HOST);
            assertThat(appearance.getGuestId()).isEqualTo(GUEST);
            assertThat(appearance.getFee()).isEqualTo(30_000);
            assertThat(appearance.getScheduledAt()).isEqualTo(recruit.getScheduledAt());
            assertThat(appearance.getRequestId()).isNull();
            assertThat(appearance.getStatus()).isEqualTo(AppearanceStatus.ESTABLISHED);
        }

        @Test
        @DisplayName("수락되지 않은 지원으로는 만들 수 없다 (코드 오류)")
        void notSelected() {
            AppearanceRecruit recruit = recruiting(3);
            AppearanceApplication application = AppearanceApplication.apply(recruit, GUEST, null, NOW);

            assertThatThrownBy(() -> Appearance.establishFrom(recruit, application, NOW))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
