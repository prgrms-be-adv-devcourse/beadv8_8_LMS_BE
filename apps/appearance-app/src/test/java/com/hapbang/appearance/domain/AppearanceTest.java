package com.hapbang.appearance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.appearance.fixture.AppearanceRequestFixture;

class AppearanceTest {

    private static final LocalDateTime SENT_AT = LocalDateTime.of(2026, 10, 13, 12, 0);
    private static final LocalDateTime ACCEPTED_AT = SENT_AT.plusDays(1);
    private static final AppearancePolicy POLICY = new AppearancePolicy(20_000, 30, Duration.ofDays(7), 2, 10);

    private static AppearanceRequest requested() {
        AppearanceRequest request = AppearanceRequest.create(1L, 2L, AppearanceRequestFixture.content(SENT_AT),
                POLICY, SENT_AT);
        request.markRequested();
        ReflectionTestUtils.setField(request, "id", 12L);
        ReflectionTestUtils.setField(request, "version", 0L);
        return request;
    }

    @Test
    @DisplayName("수락된 요청서로 출연을 만들면 ESTABLISHED, 출연료·방송 예정 일시는 요청서 값 그대로(스냅샷)")
    void establishFromAcceptedRequest() {
        AppearanceRequest request = requested();
        request.accept(2L, 0L, ACCEPTED_AT);

        Appearance appearance = Appearance.establishFrom(request, ACCEPTED_AT);

        assertThat(appearance.getStatus()).isEqualTo(AppearanceStatus.ESTABLISHED);
        assertThat(appearance.getSourceType()).isEqualTo(AppearanceSourceType.REQUEST);
        assertThat(appearance.getRequestId()).isEqualTo(12L);
        assertThat(appearance.getApplicationId()).isNull();
        assertThat(appearance.getHostId()).isEqualTo(1L);
        assertThat(appearance.getGuestId()).isEqualTo(2L);
        assertThat(appearance.getFee()).isEqualTo(request.getFee());
        assertThat(appearance.getScheduledAt()).isEqualTo(request.getScheduledAt());
        assertThat(appearance.getEstablishedAt()).isEqualTo(ACCEPTED_AT);
    }

    @Test
    @DisplayName("수락되지 않은 요청서로는 출연을 만들 수 없다 (코드 오류)")
    void onlyFromAccepted() {
        assertThatThrownBy(() -> Appearance.establishFrom(requested(), ACCEPTED_AT))
                .isInstanceOf(IllegalStateException.class);
    }
}
