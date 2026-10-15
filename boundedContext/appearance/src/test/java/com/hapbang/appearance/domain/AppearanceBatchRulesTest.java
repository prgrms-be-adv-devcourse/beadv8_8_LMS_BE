package com.hapbang.appearance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.appearance.fixture.AppearanceRecruitFixture;
import com.hapbang.appearance.fixture.AppearanceRequestFixture;

/**
 * 배치가 쓰는 도메인 규칙: 수락 기한 만료, 보류 결과의 멱등한 반영.
 */
class AppearanceBatchRulesTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 12, 0);
    private static final AppearancePolicy POLICY = new AppearancePolicy(20_000, 30, Duration.ofDays(7), 2, 10);

    /** 수락 기한 = NOW + 2일 */
    private static AppearanceRequest requested() {
        AppearanceRequest request = AppearanceRequest.create(1L, 2L, AppearanceRequestFixture.content(NOW), POLICY, NOW);
        request.markRequested();
        return request;
    }

    @Test
    @DisplayName("응답 대기 중이고 수락 기한과 같거나 지나면 만료 대상이며, 만료하면 EXPIRED")
    void expire() {
        AppearanceRequest request = requested();
        LocalDateTime deadline = request.getAcceptDeadline();

        assertThat(request.isExpirable(deadline.minusSeconds(1))).isFalse();
        assertThat(request.isExpirable(deadline)).isTrue();

        request.expire(deadline);

        assertThat(request.getStatus()).isEqualTo(AppearanceRequestStatus.EXPIRED);
        assertThat(request.isExpirable(deadline)).isFalse();
    }

    @Test
    @DisplayName("기한 전이거나 응답 대기 중이 아니면 만료할 수 없다 (코드 오류)")
    void notExpirable() {
        AppearanceRequest request = requested();
        assertThatThrownBy(() -> request.expire(NOW)).isInstanceOf(IllegalStateException.class);

        request.reject(2L, null, NOW);
        assertThatThrownBy(() -> request.expire(NOW.plusDays(3))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("요청서 보류 결과를 두 번 반영해도 그대로다 (보내기 요청과 재확인 배치가 겹쳐도 안전)")
    void requestHoldResultIsIdempotent() {
        AppearanceRequest request = requested();
        assertThat(request.markRequested()).isFalse(); // 이미 반영됨 → 바뀌지 않음 (요청 도착 이벤트를 다시 보내지 않는다)
        assertThat(request.getStatus()).isEqualTo(AppearanceRequestStatus.REQUESTED);
        assertThat(AppearanceRequest.create(1L, 2L, AppearanceRequestFixture.content(NOW), POLICY, NOW).markRequested())
                .isTrue();

        AppearanceRequest failed = AppearanceRequest.create(1L, 2L, AppearanceRequestFixture.content(NOW), POLICY, NOW);
        failed.markHoldFailed();
        failed.markHoldFailed();
        assertThat(failed.getStatus()).isEqualTo(AppearanceRequestStatus.HOLD_FAILED);
        assertThatThrownBy(failed::markRequested).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("모집글 보류 결과를 두 번 반영해도 그대로다")
    void recruitHoldResultIsIdempotent() {
        AppearanceRecruit recruit = AppearanceRecruit.create(1L, AppearanceRecruitFixture.content(NOW), POLICY, NOW);
        recruit.markRecruiting();
        recruit.markRecruiting();
        assertThat(recruit.getStatus()).isEqualTo(AppearanceRecruitStatus.RECRUITING);
        assertThatThrownBy(recruit::markHoldFailed).isInstanceOf(IllegalStateException.class);
    }
}
