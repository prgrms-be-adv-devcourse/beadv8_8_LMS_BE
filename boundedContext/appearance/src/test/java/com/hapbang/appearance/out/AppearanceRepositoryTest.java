package com.hapbang.appearance.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import com.hapbang.appearance.domain.Appearance;
import com.hapbang.appearance.domain.AppearancePolicy;
import com.hapbang.appearance.domain.AppearanceRequest;
import com.hapbang.appearance.domain.AppearanceStatus;
import com.hapbang.appearance.fixture.AppearanceRequestFixture;
import com.hapbang.testsupport.RepositoryTest;

@RepositoryTest
class AppearanceRepositoryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 12, 0);
    private static final AppearancePolicy POLICY = new AppearancePolicy(20_000, 30, Duration.ofDays(7), 2);

    @Autowired
    AppearanceRepository appearanceRepository;

    @Autowired
    AppearanceRequestRepository appearanceRequestRepository;

    private AppearanceRequest acceptedRequest() {
        AppearanceRequest request = AppearanceRequest.create(1L, 2L, AppearanceRequestFixture.content(NOW), POLICY,
                NOW);
        request.markRequested();
        appearanceRequestRepository.saveAndFlush(request);
        request.accept(2L, request.getVersion(), NOW.plusHours(1));
        return appearanceRequestRepository.saveAndFlush(request);
    }

    @Test
    @DisplayName("appearances.appearances에 저장하고 요청서 ID로 찾는다 (출연료·방송 예정 일시 스냅샷)")
    void saveAndFindByRequestId() {
        AppearanceRequest request = acceptedRequest();
        Appearance saved = appearanceRepository.saveAndFlush(Appearance.establishFrom(request, NOW.plusHours(1)));

        Appearance found = appearanceRepository.findByRequestId(request.getId()).orElseThrow();

        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getStatus()).isEqualTo(AppearanceStatus.ESTABLISHED);
        assertThat(found.getFee()).isEqualTo(AppearanceRequestFixture.FEE);
        assertThat(found.getScheduledAt()).isEqualTo(request.getScheduledAt());
        assertThat(found.getVersion()).isNotNull();
    }

    @Test
    @DisplayName("같은 요청서로 출연을 두 번 만들 수 없다 (request_id 유니크)")
    void oneAppearancePerRequest() {
        AppearanceRequest request = acceptedRequest();
        appearanceRepository.saveAndFlush(Appearance.establishFrom(request, NOW.plusHours(1)));

        assertThatThrownBy(() -> appearanceRepository.saveAndFlush(Appearance.establishFrom(request, NOW.plusHours(2))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("uk_appearances_request_id");
    }
}
