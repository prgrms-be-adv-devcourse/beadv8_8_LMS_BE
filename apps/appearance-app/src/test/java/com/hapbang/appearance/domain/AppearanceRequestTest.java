package com.hapbang.appearance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.hapbang.appearance.fixture.AppearanceRequestFixture;
import com.hapbang.shared.appearance.dto.AppearanceRequestDto;

class AppearanceRequestTest {

    private static final Long HOST = 1L;
    private static final Long GUEST = 2L;
    private static final Long OTHER = 3L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 12, 0);
    private static final AppearancePolicy POLICY = new AppearancePolicy(20_000, 30, Duration.ofDays(7), 2, 10);

    private static AppearanceRequest create(AppearanceRequestContent content) {
        return AppearanceRequest.create(HOST, GUEST, content, POLICY, NOW);
    }

    private static AppearanceRequestContent withText(String title, String url, String content, int minutes) {
        return new AppearanceRequestContent(title, url, NOW.plusDays(7), minutes, content, 30_000, NOW.plusDays(2));
    }

    private static AppearanceRequest requested() {
        AppearanceRequest request = create(AppearanceRequestFixture.content(NOW));
        request.markRequested();
        return request;
    }

    private static void assertErrorCode(Runnable action, AppearanceErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(AppearanceException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }

    @Nested
    @DisplayName("요청서 만들기")
    class Create {

        @Test
        @DisplayName("값이 올바르면 HOLD_PENDING으로 만든다")
        void holdPending() {
            AppearanceRequest request = create(AppearanceRequestFixture.content(NOW));

            assertThat(request.getStatus()).isEqualTo(AppearanceRequestStatus.HOLD_PENDING);
            assertThat(request.getHostId()).isEqualTo(HOST);
            assertThat(request.getGuestId()).isEqualTo(GUEST);
            assertThat(request.getFee()).isEqualTo(AppearanceRequestFixture.FEE);
        }

        @Test
        @DisplayName("입력 값 형식이 제한을 벗어나면 INVALID_REQUEST (제목 101자, http(s)가 아닌 URL, 설명 5,001자, 0분·1,441분)")
        void invalidFormat() {
            assertErrorCode(() -> create(withText("가".repeat(101), "https://a.com", "설명", 60)),
                    AppearanceErrorCode.INVALID_REQUEST);
            assertErrorCode(() -> create(withText("제목", "youtube.com/@host", "설명", 60)),
                    AppearanceErrorCode.INVALID_REQUEST);
            assertErrorCode(() -> create(withText("제목", "https://a.com", "가".repeat(5001), 60)),
                    AppearanceErrorCode.INVALID_REQUEST);
            assertErrorCode(() -> create(withText("제목", "https://a.com", "설명", 0)),
                    AppearanceErrorCode.INVALID_REQUEST);
            assertErrorCode(() -> create(withText("제목", "https://a.com", "설명", 1441)),
                    AppearanceErrorCode.INVALID_REQUEST);
            assertThat(create(withText("가".repeat(100), "https://a.com", "가".repeat(5000), 1440))).isNotNull();
        }

        @Test
        @DisplayName("자기 자신에게 요청하면 CANNOT_REQUEST_SELF")
        void self() {
            assertErrorCode(() -> AppearanceRequest.create(HOST, HOST, AppearanceRequestFixture.content(NOW), POLICY, NOW),
                    AppearanceErrorCode.CANNOT_REQUEST_SELF);
        }

        @Test
        @DisplayName("출연료 19,999는 FEE_BELOW_MINIMUM (메시지에 최저 출연료), 20,000은 통과")
        void minimumFee() {
            assertThatThrownBy(() -> create(AppearanceRequestFixture.content(NOW, 19_999)))
                    .isInstanceOf(AppearanceException.class)
                    .hasMessage("출연료는 최저 출연료(20,000 HP) 이상이어야 합니다.")
                    .extracting("errorCode").isEqualTo(AppearanceErrorCode.FEE_BELOW_MINIMUM);

            assertThat(create(AppearanceRequestFixture.content(NOW, 20_000)).getFee()).isEqualTo(20_000);
        }

        @Test
        @DisplayName("수락 기한이 현재와 같거나 이전이면 INVALID_REQUEST_SCHEDULE")
        void deadlineNotAfterNow() {
            assertErrorCode(() -> create(AppearanceRequestFixture.content(NOW, NOW.plusDays(7), 30_000)),
                    AppearanceErrorCode.INVALID_REQUEST_SCHEDULE);
            assertErrorCode(() -> create(AppearanceRequestFixture.content(NOW.minusMinutes(1), NOW.plusDays(7), 30_000)),
                    AppearanceErrorCode.INVALID_REQUEST_SCHEDULE);
        }

        @Test
        @DisplayName("수락 기한이 방송 예정 일시와 같거나 이후면 INVALID_REQUEST_SCHEDULE")
        void deadlineNotBeforeScheduledAt() {
            LocalDateTime scheduledAt = NOW.plusDays(7);

            assertErrorCode(() -> create(AppearanceRequestFixture.content(scheduledAt, scheduledAt, 30_000)),
                    AppearanceErrorCode.INVALID_REQUEST_SCHEDULE);
            assertErrorCode(() -> create(AppearanceRequestFixture.content(scheduledAt.plusMinutes(1), scheduledAt, 30_000)),
                    AppearanceErrorCode.INVALID_REQUEST_SCHEDULE);
        }
    }

    @Nested
    @DisplayName("HP 보류 결과 반영")
    class HoldResult {

        @Test
        @DisplayName("보류 성공이면 REQUESTED, 잔액 부족이면 HOLD_FAILED")
        void markResult() {
            AppearanceRequest held = create(AppearanceRequestFixture.content(NOW));
            AppearanceRequest failed = create(AppearanceRequestFixture.content(NOW));

            held.markRequested();
            failed.markHoldFailed();

            assertThat(held.getStatus()).isEqualTo(AppearanceRequestStatus.REQUESTED);
            assertThat(failed.getStatus()).isEqualTo(AppearanceRequestStatus.HOLD_FAILED);
        }

        @Test
        @DisplayName("이미 REQUESTED면 같은 결과는 그대로 두고(멱등), 다른 결과(HOLD_FAILED)는 반영할 수 없다")
        void onlyFromHoldPending() {
            AppearanceRequest request = requested();

            request.markRequested();
            assertThat(request.getStatus()).isEqualTo(AppearanceRequestStatus.REQUESTED);
            assertThatThrownBy(request::markHoldFailed).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("기본 정보 수정")
    class UpdateBasicInfo {

        @Test
        @DisplayName("호스트가 보낸 값만 바꾸고, null인 값은 그대로 둔다")
        void partial() {
            AppearanceRequest request = requested();
            String originalContent = request.getContent();

            request.updateBasicInfo(HOST, "새 제목", null, "https://chzzk.naver.com/host");

            assertThat(request.getTitle()).isEqualTo("새 제목");
            assertThat(request.getContent()).isEqualTo(originalContent);
            assertThat(request.getHostChannelUrl()).isEqualTo("https://chzzk.naver.com/host");
        }

        @Test
        @DisplayName("수정 값이 공백뿐이거나 형식이 틀리면 INVALID_REQUEST, 아무것도 바뀌지 않는다")
        void invalidValue() {
            AppearanceRequest request = requested();
            String originalTitle = request.getTitle();

            assertErrorCode(() -> request.updateBasicInfo(HOST, "새 제목", " ", null), AppearanceErrorCode.INVALID_REQUEST);

            assertThat(request.getTitle()).isEqualTo(originalTitle);
        }

        @Test
        @DisplayName("게스트가 수정하면 NOT_REQUEST_HOST")
        void guest() {
            AppearanceRequest request = requested();

            assertErrorCode(() -> request.updateBasicInfo(GUEST, "새 제목", null, null),
                    AppearanceErrorCode.NOT_REQUEST_HOST);
        }

        @Test
        @DisplayName("REQUESTED가 아니면 APPEARANCE_REQUEST_NOT_EDITABLE")
        void notRequested() {
            AppearanceRequest request = create(AppearanceRequestFixture.content(NOW));

            assertErrorCode(() -> request.updateBasicInfo(HOST, "새 제목", null, null),
                    AppearanceErrorCode.APPEARANCE_REQUEST_NOT_EDITABLE);
        }
    }

    @Nested
    @DisplayName("조회 권한")
    class Viewable {

        @Test
        @DisplayName("호스트·게스트는 볼 수 있다")
        void hostAndGuest() {
            AppearanceRequest request = requested();

            request.validateViewableBy(HOST);
            request.validateViewableBy(GUEST);
        }

        @Test
        @DisplayName("다른 회원은 APPEARANCE_REQUEST_NOT_FOUND")
        void other() {
            AppearanceRequest request = requested();

            assertErrorCode(() -> request.validateViewableBy(OTHER), AppearanceErrorCode.APPEARANCE_REQUEST_NOT_FOUND);
        }

        @Test
        @DisplayName("보류가 끝나지 않았거나 실패한 요청서는 호스트도 볼 수 없다")
        void hiddenStatus() {
            AppearanceRequest pending = create(AppearanceRequestFixture.content(NOW));
            AppearanceRequest failed = create(AppearanceRequestFixture.content(NOW));
            failed.markHoldFailed();

            assertErrorCode(() -> pending.validateViewableBy(HOST), AppearanceErrorCode.APPEARANCE_REQUEST_NOT_FOUND);
            assertErrorCode(() -> failed.validateViewableBy(HOST), AppearanceErrorCode.APPEARANCE_REQUEST_NOT_FOUND);
        }

        @ParameterizedTest
        @EnumSource(value = AppearanceRequestStatus.class, names = {"HOLD_PENDING", "HOLD_FAILED"},
                mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("보류가 끝난 상태는 모두 목록·상세에 보인다")
        void visibleStatus(AppearanceRequestStatus status) {
            assertThat(status.isVisible()).isTrue();
        }
    }

    @Test
    @DisplayName("다른 모듈에 넘기는 요청서 정보: 요청서 ID·호스트·게스트·제목·출연료·방송 예정 일시·수락 기한")
    void toDto() {
        AppearanceRequest request = requested();

        AppearanceRequestDto dto = request.toDto();

        assertThat(dto.hostId()).isEqualTo(HOST);
        assertThat(dto.guestId()).isEqualTo(GUEST);
        assertThat(dto.title()).isEqualTo(request.getTitle());
        assertThat(dto.fee()).isEqualTo(AppearanceRequestFixture.FEE);
        assertThat(dto.scheduledAt()).isEqualTo(request.getScheduledAt());
        assertThat(dto.acceptDeadline()).isEqualTo(request.getAcceptDeadline());
    }
}
