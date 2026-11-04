package com.hapbang.appearance.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.appearance.domain.HpTask;
import com.hapbang.appearance.domain.HpTaskReason;
import com.hapbang.appearance.domain.HpTaskRefType;
import com.hapbang.appearance.domain.HpTaskStatus;
import com.hapbang.appearance.domain.HpTaskType;
import com.hapbang.testsupport.RepositoryTest;

@RepositoryTest
class HpTaskRepositoryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 12, 0);

    @Autowired
    HpTaskRepository hpTaskRepository;

    private static HpTask release(Long requestId, String key) {
        return HpTask.release(HpTaskRefType.APPEARANCE_REQUEST, requestId, 1L, 30_000, HpTaskReason.REQUEST_REJECTED,
                key, NOW);
    }

    @Test
    @DisplayName("appearances.hp_tasks에 저장하고 조회한다")
    void saveAndFind() {
        HpTask saved = hpTaskRepository.saveAndFlush(release(12L, "APPEARANCE_REQUEST-12-RELEASE-1"));

        HpTask found = hpTaskRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getStatus()).isEqualTo(HpTaskStatus.PENDING);
        assertThat(found.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(found.getVersion()).isNotNull();
    }

    @Test
    @DisplayName("같은 멱등 키로 작업을 두 번 기록할 수 없다 (같은 HP 이동을 두 번 하지 않도록)")
    void uniqueIdempotencyKey() {
        hpTaskRepository.saveAndFlush(release(12L, "APPEARANCE_REQUEST-12-RELEASE-1"));

        assertThatThrownBy(() -> hpTaskRepository.saveAndFlush(release(12L, "APPEARANCE_REQUEST-12-RELEASE-1")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("uk_hp_tasks_idempotency_key");
    }

    @Test
    @DisplayName("멱등 키(= Billing 요청 ID)로 작업 하나를 정확히 찾는다. 같은 대상의 다른 반환과 섞이지 않는다")
    void findByIdempotencyKey() {
        HpTask closeRelease = hpTaskRepository.saveAndFlush(release(12L, "APPEARANCE_REQUEST-12-RELEASE-1"));
        hpTaskRepository.saveAndFlush(release(12L, "APPEARANCE_REQUEST-12-RELEASE-APPEARANCE-30"));

        assertThat(hpTaskRepository.findByIdempotencyKey("APPEARANCE_REQUEST-12-RELEASE-1")).get()
                .extracting(HpTask::getId).isEqualTo(closeRelease.getId());
        assertThat(hpTaskRepository.findByIdempotencyKey("APPEARANCE_REQUEST-12-RELEASE-2")).isEmpty();
    }

    @Test
    @DisplayName("재시도 대상: 대기 중이고 다음 시도 시각이 지났으며, 한 번 이상 시도했거나 만든 지 대기 시간이 지난 작업만")
    void findRunnableIds() {
        HpTask fresh = hpTaskRepository.saveAndFlush(release(12L, "APPEARANCE_REQUEST-12-RELEASE-1"));
        HpTask failed = release(13L, "APPEARANCE_REQUEST-13-RELEASE-1");
        failed.markFailed("timeout", NOW.plusMinutes(1));
        hpTaskRepository.saveAndFlush(failed);
        HpTask done = release(14L, "APPEARANCE_REQUEST-14-RELEASE-1");
        done.markDone(NOW);
        hpTaskRepository.saveAndFlush(done);
        LocalDateTime createdAt = fresh.getCreatedAt();
        PageRequest first100 = PageRequest.of(0, 100);

        // 다음 시도 시각 전: 실패한 작업은 아직 아님. 새 작업은 대기 시간 기준(createdAt 이전)이라 아님
        assertThat(hpTaskRepository.findRunnableIds(NOW, createdAt.minusMinutes(1), first100)).isEmpty();
        // 다음 시도 시각이 지남: 실패한 작업만 (새 작업은 아직 대기 시간 안)
        assertThat(hpTaskRepository.findRunnableIds(NOW.plusMinutes(1), createdAt.minusMinutes(1), first100))
                .containsExactly(failed.getId());
        // 대기 시간도 지남: 둘 다, 다음 시도 시각 순. 완료 작업은 제외
        assertThat(hpTaskRepository.findRunnableIds(NOW.plusMinutes(1), createdAt, first100))
                .containsExactly(fresh.getId(), failed.getId());
        assertThat(hpTaskRepository.findRunnableIds(NOW.plusMinutes(1), createdAt, PageRequest.of(0, 1)))
                .containsExactly(fresh.getId());
        assertThat(hpTaskRepository.countByStatus(HpTaskStatus.PENDING)).isEqualTo(2);
        assertThat(hpTaskRepository.countByStatus(HpTaskStatus.DONE)).isEqualTo(1);
    }

    @Test
    @DisplayName("관리자가 다시 시도한 작업(시도 0회)은 만든 시각이 아니라 다시 시도한 시각부터 대기 시간을 센다 (#397)")
    void runnableAfterManualRetryGrace() {
        HpTask reopened = release(12L, "APPEARANCE_REQUEST-12-RELEASE-1");
        reopened.markDead("HOLD_NOT_FOUND: HP 보류를 찾을 수 없습니다.", NOW);
        hpTaskRepository.saveAndFlush(reopened);
        // 만든 시각(저장 시각)보다 3시간 뒤에 다시 시도해, 만든 시각 기준이면 대기 시간이 지난 상태로 만든다
        LocalDateTime retriedAt = reopened.getCreatedAt().plusHours(3);
        reopened.reopenForManualRetry(900L, retriedAt);
        hpTaskRepository.saveAndFlush(reopened);
        PageRequest first100 = PageRequest.of(0, 100);

        // 만든 지는 오래됐지만 다시 시도한 지 대기 시간이 안 지남: 아님
        assertThat(hpTaskRepository.findRunnableIds(retriedAt, retriedAt.minusMinutes(1), first100)).isEmpty();
        // 다시 시도한 지 대기 시간이 지남: 대상
        assertThat(hpTaskRepository.findRunnableIds(retriedAt.plusMinutes(1), retriedAt, first100))
                .containsExactly(reopened.getId());
    }

    @Test
    @DisplayName("다시 시도 컬럼은 셋 다 비었거나 셋 다 있다 (ck_hp_tasks_manual_retry)")
    void manualRetryColumnsTogether() {
        HpTask broken = release(13L, "APPEARANCE_REQUEST-13-RELEASE-1");
        ReflectionTestUtils.setField(broken, "manualRetryCount", 1);

        assertThatThrownBy(() -> hpTaskRepository.saveAndFlush(broken))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("ck_hp_tasks_manual_retry");
    }

    @Test
    @DisplayName("위약금 정산 작업을 저장한다 (받는 회원·위약금·출연 ID). 반환 작업에 위약금 값이 있으면 ck_hp_tasks_receiver에 걸린다")
    void savePenalty() {
        HpTask saved = hpTaskRepository.saveAndFlush(HpTask.penalty(HpTaskRefType.APPEARANCE_REQUEST, 12L, 1L, 30_000,
                2L, 9_000, 30L, HpTaskReason.HOST_CANCELED, "APPEARANCE_REQUEST-12-PENALTY-APPEARANCE-30", NOW));

        HpTask found = hpTaskRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getTaskType()).isEqualTo(HpTaskType.PENALTY);
        assertThat(found.getReceiverId()).isEqualTo(2L);
        assertThat(found.getPayoutAmount()).isEqualTo(9_000);
        assertThat(found.getAppearanceId()).isEqualTo(30L);

        HpTask broken = release(13L, "APPEARANCE_REQUEST-13-RELEASE-1");
        ReflectionTestUtils.setField(broken, "payoutAmount", 1L);
        assertThatThrownBy(() -> hpTaskRepository.saveAndFlush(broken))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("ck_hp_tasks_receiver");
    }

    @Test
    @DisplayName("지급 작업을 저장한다 (받는 회원·지급액 = 출연료·출연 ID, #282). 지급액이 출연료와 다르면 ck_hp_tasks_receiver에 걸린다")
    void savePayout() {
        HpTask saved = hpTaskRepository.saveAndFlush(HpTask.payout(HpTaskRefType.APPEARANCE_RECRUIT, 5L, 1L, 30_000,
                2L, 31L, HpTaskReason.APPEARANCE_CONFIRMED, "APPEARANCE_RECRUIT-5-PAYOUT-APPEARANCE-31", NOW));

        HpTask found = hpTaskRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getTaskType()).isEqualTo(HpTaskType.PAYOUT);
        assertThat(found.getReason()).isEqualTo(HpTaskReason.APPEARANCE_CONFIRMED);
        assertThat(found.getReceiverId()).isEqualTo(2L);
        assertThat(found.getPayoutAmount()).isEqualTo(30_000);
        assertThat(found.getAppearanceId()).isEqualTo(31L);

        HpTask broken = HpTask.payout(HpTaskRefType.APPEARANCE_RECRUIT, 5L, 1L, 30_000, 3L, 32L,
                HpTaskReason.APPEARANCE_CONFIRMED, "APPEARANCE_RECRUIT-5-PAYOUT-APPEARANCE-32", NOW);
        ReflectionTestUtils.setField(broken, "payoutAmount", 29_999L);
        assertThatThrownBy(() -> hpTaskRepository.saveAndFlush(broken))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("ck_hp_tasks_receiver");
    }
}
