package com.hapbang.appearance.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import com.hapbang.appearance.domain.Appearance;
import com.hapbang.appearance.domain.AppearanceCancelRequest;
import com.hapbang.appearance.domain.AppearanceCancelRequestStatus;
import com.hapbang.appearance.domain.AppearancePolicy;
import com.hapbang.appearance.domain.AppearanceRequest;
import com.hapbang.appearance.domain.AppearanceStatus;
import com.hapbang.appearance.fixture.AppearanceRequestFixture;
import com.hapbang.testsupport.RepositoryTest;

@RepositoryTest
class AppearanceCancelRequestRepositoryTest {

    private static final Long HOST = 1L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 12, 0);
    private static final AppearancePolicy POLICY = new AppearancePolicy(20_000, 30, Duration.ofDays(7), 2, 10);
    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "id"));

    @Autowired
    AppearanceCancelRequestRepository appearanceCancelRequestRepository;

    @Autowired
    AppearanceRepository appearanceRepository;

    @Autowired
    AppearanceRequestRepository appearanceRequestRepository;

    /** 호스트 1이 게스트에게 보낸 요청서가 수락돼 성립한 출연. 방송 예정 일시 = NOW + 7일 */
    private Appearance established(Long guestId) {
        AppearanceRequest request = AppearanceRequest.create(HOST, guestId, AppearanceRequestFixture.content(NOW),
                POLICY, NOW);
        request.markRequested();
        appearanceRequestRepository.saveAndFlush(request);
        request.accept(guestId, request.getVersion(), NOW.plusHours(1));
        appearanceRequestRepository.saveAndFlush(request);
        return appearanceRepository.saveAndFlush(Appearance.establishFrom(request, NOW.plusHours(1)));
    }

    private AppearanceCancelRequest saveRequest(Appearance appearance, int previousCount) {
        AppearanceCancelRequest cancelRequest = AppearanceCancelRequest.request(appearance, appearance.getGuestId(),
                "일정이 생겼습니다.", previousCount, POLICY, NOW.plusHours(2));
        appearanceRepository.saveAndFlush(appearance);
        return appearanceCancelRequestRepository.saveAndFlush(cancelRequest);
    }

    @Test
    @DisplayName("appearances.cancel_requests에 저장하고, 출연별 처리 중 요청·요청 수·이력(순번 순)을 찾는다")
    void saveAndFind() {
        Appearance appearance = established(2L);
        AppearanceCancelRequest first = saveRequest(appearance, 0);
        first.reject("안 됩니다.", NOW.plusHours(3));
        appearanceCancelRequestRepository.saveAndFlush(first);
        appearance.keepAfterCancelRejected();
        AppearanceCancelRequest second = saveRequest(appearance, 1);

        assertThat(appearanceCancelRequestRepository.countByAppearanceId(appearance.getId())).isEqualTo(2);
        assertThat(appearanceCancelRequestRepository.findByAppearanceIdAndStatus(appearance.getId(),
                AppearanceCancelRequestStatus.REQUESTED)).get().extracting(AppearanceCancelRequest::getId)
                .isEqualTo(second.getId());
        assertThat(appearanceCancelRequestRepository.findByAppearanceIdOrderBySequence(appearance.getId()))
                .extracting(AppearanceCancelRequest::getSequence).containsExactly(1, 2);
        assertThat(second.getCreatedAt()).isNotNull();
        assertThat(second.getVersion()).isNotNull();
    }

    @Test
    @DisplayName("처리 중인 취소 요청은 출연당 하나뿐이다 (부분 유니크 인덱스 uk_appearance_cancel_requests_pending)")
    void onePendingPerAppearance() {
        Appearance appearance = established(2L);
        saveRequest(appearance, 0);
        appearance.keepAfterCancelRejected(); // 출연만 되돌려 두 번째 요청을 만들 수 있게 한다 (첫 요청은 처리 중 그대로)
        AppearanceCancelRequest second = AppearanceCancelRequest.request(appearance, 2L, "또 요청", 1, POLICY,
                NOW.plusHours(2));

        assertThatThrownBy(() -> appearanceCancelRequestRepository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("uk_appearance_cancel_requests_pending");
    }

    @Test
    @DisplayName("출연 하나에서 순번은 겹치지 않는다 (uk_appearance_cancel_requests_appearance_sequence)")
    void uniqueSequence() {
        Appearance appearance = established(2L);
        AppearanceCancelRequest first = saveRequest(appearance, 0);
        first.reject(null, NOW.plusHours(3));
        appearanceCancelRequestRepository.saveAndFlush(first);
        appearance.keepAfterCancelRejected();
        AppearanceCancelRequest sameSequence = AppearanceCancelRequest.request(appearance, 2L, "다시", 0, POLICY,
                NOW.plusHours(4));

        assertThatThrownBy(() -> appearanceCancelRequestRepository.saveAndFlush(sameSequence))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("uk_appearance_cancel_requests_appearance_sequence");
    }

    @Test
    @DisplayName("자동 거절 대상: 처리 중이고 출연의 방송 예정 일시가 기준 시각과 같거나 지난 출연만 (응답된 요청 제외)")
    void findDue() {
        Appearance pending = established(2L);
        saveRequest(pending, 0);
        Appearance answered = established(3L);
        AppearanceCancelRequest rejected = saveRequest(answered, 0);
        rejected.reject(null, NOW.plusHours(3));
        appearanceCancelRequestRepository.saveAndFlush(rejected);
        LocalDateTime scheduledAt = pending.getScheduledAt();

        assertThat(appearanceCancelRequestRepository.findAppearanceIdsDue(AppearanceCancelRequestStatus.REQUESTED,
                scheduledAt.minusSeconds(1), FIRST_PAGE)).isEmpty();
        assertThat(appearanceCancelRequestRepository.findAppearanceIdsDue(AppearanceCancelRequestStatus.REQUESTED,
                scheduledAt, PageRequest.of(0, 20))).containsExactly(pending.getId());
    }

    @Test
    @DisplayName("출연: 호스트·게스트별 목록(상태 필터), 출처 모집글 ID만 조회(요청 출처는 null), 잠금 조회")
    void appearanceQueries() {
        Appearance toGuest2 = established(2L);
        Appearance toGuest3 = established(3L);

        assertThat(appearanceRepository.findByHostIdAndStatusIn(HOST, List.of(AppearanceStatus.ESTABLISHED),
                FIRST_PAGE).getContent()).extracting(Appearance::getId)
                .containsExactly(toGuest3.getId(), toGuest2.getId());
        assertThat(appearanceRepository.findByGuestIdAndStatusIn(3L, List.of(AppearanceStatus.values()), FIRST_PAGE)
                .getContent()).extracting(Appearance::getId).containsExactly(toGuest3.getId());
        assertThat(appearanceRepository.findByGuestIdAndStatusIn(3L, List.of(AppearanceStatus.CANCEL_REQUESTED),
                FIRST_PAGE).getContent()).isEmpty();
        assertThat(appearanceRepository.findSourceRefById(toGuest2.getId())).get()
                .extracting(AppearanceRepository.SourceRef::getRecruitId).isNull();
        assertThat(appearanceRepository.findSourceRefById(999_999L)).isEmpty();
        assertThat(appearanceRepository.findByIdForUpdate(toGuest2.getId())).isPresent();
    }
}
