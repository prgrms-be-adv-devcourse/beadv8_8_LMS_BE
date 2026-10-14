package com.hapbang.appearance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.hapbang.appearance.fixture.AppearanceRecruitFixture;

class AppearanceRecruitTest {

    private static final Long HOST = 1L;
    private static final Long OTHER = 3L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 12, 0);
    private static final AppearancePolicy POLICY = new AppearancePolicy(20_000, 30, Duration.ofDays(7), 2, 10);

    private static AppearanceRecruit create(AppearanceRecruitContent content) {
        return AppearanceRecruit.create(HOST, content, POLICY, NOW);
    }

    private static AppearanceRecruit recruiting() {
        AppearanceRecruit recruit = create(AppearanceRecruitFixture.content(NOW));
        recruit.markRecruiting();
        return recruit;
    }

    private static void assertErrorCode(Runnable action, AppearanceErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(AppearanceException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }

    @Nested
    @DisplayName("모집글 만들기")
    class Create {

        @Test
        @DisplayName("값이 올바르면 HOLD_PENDING, 성립 인원 0으로 만들고 보류 금액은 1인당 출연료 × 모집 인원이다")
        void holdPending() {
            AppearanceRecruit recruit = create(AppearanceRecruitFixture.content(NOW));

            assertThat(recruit.getStatus()).isEqualTo(AppearanceRecruitStatus.HOLD_PENDING);
            assertThat(recruit.getHostId()).isEqualTo(HOST);
            assertThat(recruit.getSelectedCount()).isZero();
            assertThat(recruit.totalFee()).isEqualTo(AppearanceRecruitFixture.TOTAL_FEE);
        }

        @ParameterizedTest(name = "모집 인원 {0}명")
        @ValueSource(ints = {1, 10})
        @DisplayName("모집 인원 1명과 10명은 허용한다")
        void recruitCountBoundary(int recruitCount) {
            AppearanceRecruit recruit = create(AppearanceRecruitFixture.content(NOW, 30_000, recruitCount));

            assertThat(recruit.getRecruitCount()).isEqualTo(recruitCount);
        }

        @ParameterizedTest(name = "모집 인원 {0}명")
        @ValueSource(ints = {0, 11})
        @DisplayName("모집 인원이 1~10명이 아니면 RECRUIT_COUNT_OUT_OF_RANGE")
        void recruitCountOutOfRange(int recruitCount) {
            assertThatThrownBy(() -> create(AppearanceRecruitFixture.content(NOW, 30_000, recruitCount)))
                    .isInstanceOf(AppearanceException.class)
                    .hasMessage("모집 인원은 1명 이상 10명 이하여야 합니다.")
                    .extracting("errorCode").isEqualTo(AppearanceErrorCode.RECRUIT_COUNT_OUT_OF_RANGE);
        }

        @Test
        @DisplayName("1인당 출연료가 최저 출연료 미만이면 FEE_BELOW_MINIMUM (20,000은 허용)")
        void feeBelowMinimum() {
            assertThatThrownBy(() -> create(AppearanceRecruitFixture.content(NOW, 19_999, 3)))
                    .isInstanceOf(AppearanceException.class)
                    .hasMessage("1인당 출연료는 최저 출연료(20,000 HP) 이상이어야 합니다.")
                    .extracting("errorCode").isEqualTo(AppearanceErrorCode.FEE_BELOW_MINIMUM);
            assertThat(create(AppearanceRecruitFixture.content(NOW, 20_000, 3)).getFeePerPerson()).isEqualTo(20_000);
        }

        @Test
        @DisplayName("보류 금액을 계산할 수 없을 만큼 큰 1인당 출연료는 INVALID_REQUEST")
        void feeOverflow() {
            assertErrorCode(() -> create(AppearanceRecruitFixture.content(NOW, Long.MAX_VALUE / 2, 3)),
                    AppearanceErrorCode.INVALID_REQUEST);
        }

        @Test
        @DisplayName("모집 마감 일시가 현재 이전·같음이거나 방송 예정 일시 이후·같음이면 INVALID_RECRUIT_SCHEDULE")
        void invalidSchedule() {
            LocalDateTime scheduledAt = NOW.plusDays(7);
            assertErrorCode(() -> create(AppearanceRecruitFixture.content(NOW, scheduledAt, 30_000, 3)),
                    AppearanceErrorCode.INVALID_RECRUIT_SCHEDULE);
            assertErrorCode(() -> create(AppearanceRecruitFixture.content(NOW.minusMinutes(1), scheduledAt, 30_000, 3)),
                    AppearanceErrorCode.INVALID_RECRUIT_SCHEDULE);
            assertErrorCode(() -> create(AppearanceRecruitFixture.content(scheduledAt, scheduledAt, 30_000, 3)),
                    AppearanceErrorCode.INVALID_RECRUIT_SCHEDULE);
        }

        @Test
        @DisplayName("제목·URL·설명·예상 출연 시간 형식은 요청서와 같은 제한을 쓴다")
        void textRules() {
            AppearanceRecruitContent base = AppearanceRecruitFixture.content(NOW);
            assertErrorCode(() -> create(new AppearanceRecruitContent(" ", base.hostChannelUrl(), base.scheduledAt(),
                    60, base.content(), 30_000, 3, base.recruitDeadline())), AppearanceErrorCode.INVALID_REQUEST);
            assertErrorCode(() -> create(new AppearanceRecruitContent(base.title(), "ftp://host", base.scheduledAt(),
                    60, base.content(), 30_000, 3, base.recruitDeadline())), AppearanceErrorCode.INVALID_REQUEST);
            assertErrorCode(() -> create(new AppearanceRecruitContent(base.title(), base.hostChannelUrl(),
                    base.scheduledAt(), 0, base.content(), 30_000, 3, base.recruitDeadline())),
                    AppearanceErrorCode.INVALID_REQUEST);
        }
    }

    @Nested
    @DisplayName("보류 결과 반영")
    class Hold {

        @Test
        @DisplayName("보류 성공은 RECRUITING, 거절은 HOLD_FAILED. HOLD_PENDING이 아니면 코드 오류")
        void markHoldResult() {
            AppearanceRecruit recruit = recruiting();
            assertThat(recruit.getStatus()).isEqualTo(AppearanceRecruitStatus.RECRUITING);
            assertThatThrownBy(recruit::markHoldFailed).isInstanceOf(IllegalStateException.class);

            AppearanceRecruit failed = create(AppearanceRecruitFixture.content(NOW));
            failed.markHoldFailed();
            assertThat(failed.getStatus()).isEqualTo(AppearanceRecruitStatus.HOLD_FAILED);
        }
    }

    @Nested
    @DisplayName("기본 정보 수정")
    class Update {

        @Test
        @DisplayName("호스트가 모집 중에 보낸 값만 바꾼다 (null은 그대로)")
        void update() {
            AppearanceRecruit recruit = recruiting();

            recruit.updateBasicInfo(HOST, "새 제목", null, "https://chzzk.naver.com/host", NOW);

            assertThat(recruit.getTitle()).isEqualTo("새 제목");
            assertThat(recruit.getContent()).isEqualTo("같이 신작 게임을 플레이할 게스트를 모집합니다.");
            assertThat(recruit.getHostChannelUrl()).isEqualTo("https://chzzk.naver.com/host");
        }

        @Test
        @DisplayName("호스트가 아니면 NOT_RECRUIT_HOST")
        void notHost() {
            assertErrorCode(() -> recruiting().updateBasicInfo(OTHER, "새 제목", null, null, NOW),
                    AppearanceErrorCode.NOT_RECRUIT_HOST);
        }

        @Test
        @DisplayName("모집 중이 아니거나, 모집 중이어도 마감 일시가 지났으면 APPEARANCE_RECRUIT_NOT_EDITABLE")
        void notEditable() {
            AppearanceRecruit pending = create(AppearanceRecruitFixture.content(NOW));
            assertErrorCode(() -> pending.updateBasicInfo(HOST, "새 제목", null, null, NOW),
                    AppearanceErrorCode.APPEARANCE_RECRUIT_NOT_EDITABLE);

            AppearanceRecruit recruit = recruiting();
            assertErrorCode(() -> recruit.updateBasicInfo(HOST, "새 제목", null, null, recruit.getRecruitDeadline()),
                    AppearanceErrorCode.APPEARANCE_RECRUIT_NOT_EDITABLE);
        }

        @Test
        @DisplayName("값 형식이 틀리면 INVALID_REQUEST이고 아무것도 바꾸지 않는다")
        void invalidValue() {
            AppearanceRecruit recruit = recruiting();

            assertErrorCode(() -> recruit.updateBasicInfo(HOST, "새 제목", null, "not-url", NOW),
                    AppearanceErrorCode.INVALID_REQUEST);
            assertThat(recruit.getTitle()).isEqualTo("주말 게임 합방 게스트 모집");
        }
    }

    @Test
    @DisplayName("보류 처리 중·실패 모집글은 상세에서 숨긴다 (APPEARANCE_RECRUIT_NOT_FOUND)")
    void visibility() {
        AppearanceRecruit pending = create(AppearanceRecruitFixture.content(NOW));
        assertErrorCode(pending::validateVisible, AppearanceErrorCode.APPEARANCE_RECRUIT_NOT_FOUND);

        recruiting().validateVisible();
    }
}
