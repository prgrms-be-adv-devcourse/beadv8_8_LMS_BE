package com.hapbang.appearance.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import com.hapbang.appearance.domain.AppearancePolicy;
import com.hapbang.appearance.domain.AppearanceRequest;
import com.hapbang.appearance.domain.AppearanceRequestStatus;
import com.hapbang.appearance.fixture.AppearanceRequestFixture;
import com.hapbang.testsupport.RepositoryTest;

@RepositoryTest
class AppearanceRequestRepositoryTest {

    private static final Long HOST = 1L;
    private static final Long GUEST = 2L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 12, 0);
    private static final AppearancePolicy POLICY = new AppearancePolicy(20_000, 30, Duration.ofDays(7), 2, 10);

    @Autowired
    AppearanceRequestRepository appearanceRequestRepository;

    private AppearanceRequest newRequest(Long hostId, Long guestId) {
        return AppearanceRequest.create(hostId, guestId, AppearanceRequestFixture.content(NOW), POLICY, NOW);
    }

    @Test
    @DisplayName("appearances.requests에 저장하고 조회한다 (ID 자동 생성, 버전·생성 시각 기록)")
    void saveAndFind() {
        AppearanceRequest saved = appearanceRequestRepository.saveAndFlush(newRequest(HOST, GUEST));

        AppearanceRequest found = appearanceRequestRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getId()).isPositive();
        assertThat(found.getStatus()).isEqualTo(AppearanceRequestStatus.HOLD_PENDING);
        assertThat(found.getContent()).isEqualTo("같이 신작 게임을 플레이하는 콘텐츠입니다.");
        assertThat(found.getAcceptDeadline()).isEqualTo(NOW.plusDays(2));
        assertThat(found.getVersion()).isNotNull();
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("같은 호스트·게스트의 진행 중(HOLD_PENDING·REQUESTED) 요청서가 있으면 DB가 두 번째 저장을 막는다")
    void pendingHostGuestUniqueIndex() {
        AppearanceRequest first = newRequest(HOST, GUEST);
        first.markRequested();
        appearanceRequestRepository.saveAndFlush(first);

        assertThatThrownBy(() -> appearanceRequestRepository.saveAndFlush(newRequest(HOST, GUEST)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("uk_appearance_requests_pending_host_guest");
    }

    @Test
    @DisplayName("보류에 실패한(HOLD_FAILED) 요청서는 제한에 포함되지 않아 같은 게스트에게 다시 보낼 수 있다")
    void holdFailedIsNotPending() {
        AppearanceRequest failed = newRequest(HOST, GUEST);
        failed.markHoldFailed();
        appearanceRequestRepository.saveAndFlush(failed);

        appearanceRequestRepository.saveAndFlush(newRequest(HOST, GUEST));

        assertThat(appearanceRequestRepository.existsByHostIdAndGuestIdAndStatusIn(HOST, GUEST,
                AppearanceRequestStatus.IN_PROGRESS)).isTrue();
        assertThat(appearanceRequestRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("방향이 다르거나 게스트가 다르면 제한과 관계없다")
    void otherPairs() {
        appearanceRequestRepository.saveAndFlush(newRequest(HOST, GUEST));
        appearanceRequestRepository.saveAndFlush(newRequest(GUEST, HOST));
        appearanceRequestRepository.saveAndFlush(newRequest(HOST, 3L));

        assertThat(appearanceRequestRepository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("목록은 보이는 상태만, 호스트·게스트 기준으로 페이지 조회한다")
    void listByVisibleStatus() {
        AppearanceRequest requested = newRequest(HOST, GUEST);
        requested.markRequested();
        appearanceRequestRepository.saveAndFlush(requested);
        AppearanceRequest failed = newRequest(HOST, 3L);
        failed.markHoldFailed();
        appearanceRequestRepository.saveAndFlush(failed);
        appearanceRequestRepository.saveAndFlush(newRequest(HOST, 4L)); // HOLD_PENDING

        PageRequest pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        Page<AppearanceRequest> sent = appearanceRequestRepository.findByHostIdAndStatusIn(HOST,
                AppearanceRequestStatus.VISIBLE, pageable);
        Page<AppearanceRequest> received = appearanceRequestRepository.findByGuestIdAndStatusIn(GUEST,
                AppearanceRequestStatus.VISIBLE, pageable);

        assertThat(sent.getContent()).extracting(AppearanceRequest::getId).containsExactly(requested.getId());
        assertThat(sent.getTotalElements()).isEqualTo(1);
        assertThat(received.getContent()).extracting(AppearanceRequest::getId).containsExactly(requested.getId());
    }
}
