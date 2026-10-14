package com.hapbang.appearance.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

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
    @DisplayName("대상별 가장 최근 작업을 찾는다")
    void findLatestByRef() {
        hpTaskRepository.saveAndFlush(release(12L, "APPEARANCE_REQUEST-12-RELEASE-1"));
        HpTask latest = hpTaskRepository.saveAndFlush(release(12L, "APPEARANCE_REQUEST-12-RELEASE-2"));
        hpTaskRepository.saveAndFlush(release(13L, "APPEARANCE_REQUEST-13-RELEASE-1"));

        HpTask found = hpTaskRepository.findFirstByRefTypeAndRefIdAndTaskTypeOrderByIdDesc(
                HpTaskRefType.APPEARANCE_REQUEST, 12L, HpTaskType.RELEASE).orElseThrow();

        assertThat(found.getId()).isEqualTo(latest.getId());
    }
}
