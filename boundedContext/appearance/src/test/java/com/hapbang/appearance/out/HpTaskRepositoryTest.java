package com.hapbang.appearance.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;

import com.hapbang.appearance.domain.HpTask;
import com.hapbang.appearance.domain.HpTaskReason;
import com.hapbang.appearance.domain.HpTaskRefType;
import com.hapbang.appearance.domain.HpTaskStatus;
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
                .hasSize(1);
        assertThat(hpTaskRepository.countByStatus(HpTaskStatus.PENDING)).isEqualTo(2);
        assertThat(hpTaskRepository.countByStatus(HpTaskStatus.DONE)).isEqualTo(1);
    }
}
