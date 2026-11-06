package com.hapbang.appearance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.appearance.fixture.AppearanceRequestFixture;
import com.hapbang.shared.appearance.domain.AppearanceResult;
import com.hapbang.shared.appearance.dto.AppearanceSyncDto;
import com.hapbang.shared.appearance.event.AppearanceChangedEvent;

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

    private static Appearance established() {
        AppearanceRequest request = requested();
        request.accept(2L, 0L, ACCEPTED_AT);
        return Appearance.establishFrom(request, ACCEPTED_AT);
    }

    @Test
    @DisplayName("동기화 사본(#713): ID·호스트·게스트·공개 결과·방송 예정 일시·version. 변경 이벤트도 같은 값을 감싼다")
    void syncDto() {
        Appearance appearance = established();
        ReflectionTestUtils.setField(appearance, "id", 30L);
        ReflectionTestUtils.setField(appearance, "version", 3L);

        AppearanceSyncDto expected = new AppearanceSyncDto(30L, 1L, 2L, AppearanceResult.SCHEDULED,
                appearance.getScheduledAt(), 3L);
        assertThat(appearance.toSyncDto()).isEqualTo(expected);
        assertThat(appearance.toChangedEvent()).isEqualTo(new AppearanceChangedEvent(expected));
    }

    @Test
    @DisplayName("동기화 사본의 방송 예정 일시는 DB 컬럼과 같은 마이크로초까지만 둔다 (이벤트 값 = 내부 API 값)")
    void syncDtoScheduledAtMicros() {
        Appearance appearance = established();
        ReflectionTestUtils.setField(appearance, "id", 30L);
        ReflectionTestUtils.setField(appearance, "version", 0L);
        LocalDateTime scheduledAt = appearance.getScheduledAt();
        ReflectionTestUtils.setField(appearance, "scheduledAt", scheduledAt.plusNanos(1_234_567));

        assertThat(appearance.toSyncDto().scheduledAt()).isEqualTo(scheduledAt.plusNanos(1_234_000));
    }

    @Test
    @DisplayName("DB에 반영되기 전(ID·version 없음)에는 동기화 사본을 만들 수 없다 (flush 전에 만든 이벤트를 막는다)")
    void syncDtoNeedsFlush() {
        Appearance appearance = established();

        assertThatThrownBy(appearance::toSyncDto).isInstanceOf(IllegalStateException.class);
        ReflectionTestUtils.setField(appearance, "id", 30L);
        assertThatThrownBy(appearance::toChangedEvent).isInstanceOf(IllegalStateException.class);
    }
}
