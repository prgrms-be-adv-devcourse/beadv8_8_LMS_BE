package com.hapbang.lecture.out;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import com.hapbang.lecture.domain.LectureHpTask;
import com.hapbang.lecture.domain.LectureHpTaskOutcome;
import com.hapbang.lecture.domain.LectureHpTaskPurpose;
import com.hapbang.lecture.domain.LectureHpTaskStatus;
import com.hapbang.testsupport.RepositoryTest;

import jakarta.persistence.EntityManager;

// HP 작업 저장과 조건부 UPDATE 선점 (D57 (2))
@RepositoryTest
class LectureHpTaskRepositoryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 12, 0);
    private static final int MAX_ATTEMPTS = 10;

    @Autowired
    private LectureHpTaskRepository lectureHpTaskRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void 작업을_저장하면_PENDING이고_시도_횟수는_0이다() {
        LectureHpTask saved = lectureHpTaskRepository.saveAndFlush(cancelTask());
        entityManager.clear();

        LectureHpTask found = lectureHpTaskRepository.findByRequestId("LECTURE-CANCEL-31").orElseThrow();
        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getStatus()).isEqualTo(LectureHpTaskStatus.PENDING);
        assertThat(found.getAttemptCount()).isZero();
        assertThat(found.getTotalAttemptCount()).isZero();
        assertThat(found.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(found.getPayloadFingerprint()).hasSize(64);
    }

    @Test
    void 선점하면_PROCESSING이_되고_횟수가_오른다() {
        Long id = lectureHpTaskRepository.saveAndFlush(cancelTask()).getId();
        UUID token = UUID.randomUUID();

        int claimed = lectureHpTaskRepository.claim(id, token, NOW, NOW.plusMinutes(5), MAX_ATTEMPTS);
        entityManager.clear();

        assertThat(claimed).isEqualTo(1);
        LectureHpTask task = lectureHpTaskRepository.findById(id).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(LectureHpTaskStatus.PROCESSING);
        assertThat(task.getClaimToken()).isEqualTo(token);
        assertThat(task.getLeaseUntil()).isEqualTo(NOW.plusMinutes(5));
        assertThat(task.getAttemptCount()).isEqualTo(1);
        assertThat(task.getTotalAttemptCount()).isEqualTo(1);
        assertThat(task.getVersion()).isEqualTo(1);
    }

    @Test
    void 다음_시도_시각_전이거나_선점이_살아_있으면_선점하지_않는다() {
        Long id = lectureHpTaskRepository.saveAndFlush(cancelTask()).getId();

        assertThat(lectureHpTaskRepository.claim(id, UUID.randomUUID(), NOW.minusSeconds(1), NOW, MAX_ATTEMPTS))
                .isZero();

        lectureHpTaskRepository.claim(id, UUID.randomUUID(), NOW, NOW.plusMinutes(5), MAX_ATTEMPTS);
        assertThat(lectureHpTaskRepository.claim(id, UUID.randomUUID(), NOW.plusMinutes(4), NOW.plusMinutes(9),
                MAX_ATTEMPTS)).isZero();
        // 선점이 만료되면 다시 선점한다
        assertThat(lectureHpTaskRepository.claim(id, UUID.randomUUID(), NOW.plusMinutes(6), NOW.plusMinutes(11),
                MAX_ATTEMPTS)).isEqualTo(1);
    }

    @Test
    void 자동_재시도_한도에_닿은_작업은_선점하지_않는다() {
        Long id = lectureHpTaskRepository.saveAndFlush(cancelTask()).getId();
        LocalDateTime time = NOW;
        for (int i = 0; i < 2; i++) {
            assertThat(lectureHpTaskRepository.claim(id, UUID.randomUUID(), time, time.plusMinutes(5), 2)).isEqualTo(1);
            time = time.plusMinutes(6);
        }

        assertThat(lectureHpTaskRepository.claim(id, UUID.randomUUID(), time, time.plusMinutes(5), 2)).isZero();
    }

    @Test
    void 실행할_작업은_시각이_지난_대기_작업과_선점이_만료된_작업이다() {
        Long due = lectureHpTaskRepository.saveAndFlush(cancelTask()).getId();
        Long expired = lectureHpTaskRepository.saveAndFlush(task("LECTURE-CANCEL-32", 32L)).getId();
        Long running = lectureHpTaskRepository.saveAndFlush(task("LECTURE-CANCEL-33", 33L)).getId();
        lectureHpTaskRepository.claim(expired, UUID.randomUUID(), NOW, NOW.plusMinutes(5), MAX_ATTEMPTS);
        lectureHpTaskRepository.claim(running, UUID.randomUUID(), NOW.plusMinutes(5), NOW.plusMinutes(10),
                MAX_ATTEMPTS);

        assertThat(lectureHpTaskRepository.findRunnableIds(NOW.plusMinutes(6), PageRequest.of(0, 100)))
                .containsExactly(due, expired);
        assertThat(lectureHpTaskRepository.findRunnableIds(NOW.plusMinutes(6), PageRequest.of(0, 1)))
                .containsExactly(due);
    }

    @Test
    void 실행하지_않은_주문의_보류_작업만_SKIPPED로_닫는다() {
        LectureHpTask claimed = lectureHpTaskRepository.save(holdTask(3L, 31L));
        LectureHpTask retrying = lectureHpTaskRepository.save(holdTask(3L, 32L));
        LectureHpTask unstarted = lectureHpTaskRepository.save(holdTask(3L, 33L));
        LectureHpTask otherOrder = lectureHpTaskRepository.save(holdTask(4L, 41L));
        LectureHpTask cancel = lectureHpTaskRepository.saveAndFlush(task("LECTURE-CANCEL-34", 34L));
        lectureHpTaskRepository.claim(claimed.getId(), UUID.randomUUID(), NOW, NOW.plusMinutes(5), MAX_ATTEMPTS);
        lectureHpTaskRepository.claim(retrying.getId(), UUID.randomUUID(), NOW, NOW.plusMinutes(5), MAX_ATTEMPTS);
        LectureHpTask failedOnce = lectureHpTaskRepository.findById(retrying.getId()).orElseThrow();
        failedOnce.scheduleRetry("UNKNOWN_RESULT", NOW, NOW);
        lectureHpTaskRepository.flush();

        int skipped = lectureHpTaskRepository.skipUnstartedHolds(3L, NOW.plusSeconds(1));
        entityManager.clear();

        assertThat(skipped).isEqualTo(1);
        assertThat(lectureHpTaskRepository.findByOrderIdIn(List.of(3L, 4L)))
                .extracting(LectureHpTask::getId, LectureHpTask::getStatus, LectureHpTask::getOutcome)
                .containsExactlyInAnyOrder(
                        tuple(claimed.getId(), LectureHpTaskStatus.PROCESSING, null),
                        tuple(retrying.getId(), LectureHpTaskStatus.PENDING, null),
                        tuple(unstarted.getId(), LectureHpTaskStatus.COMPLETED, LectureHpTaskOutcome.SKIPPED),
                        tuple(otherOrder.getId(), LectureHpTaskStatus.PENDING, null),
                        tuple(cancel.getId(), LectureHpTaskStatus.PENDING, null));
        assertThat(lectureHpTaskRepository.findById(unstarted.getId()).orElseThrow().getCompletedAt())
                .isEqualTo(NOW.plusSeconds(1));
    }

    private static LectureHpTask cancelTask() {
        return task("LECTURE-CANCEL-31", 31L);
    }

    private static LectureHpTask holdTask(Long orderId, Long orderItemId) {
        return LectureHpTask.create(LectureHpTaskPurpose.ORDER_HOLD, "LECTURE-HOLD-" + orderItemId, orderId,
                orderItemId, 7L, null, 10_000L, NOW);
    }

    private static LectureHpTask task(String requestId, Long orderItemId) {
        return LectureHpTask.create(LectureHpTaskPurpose.CANCEL, requestId, 3L, orderItemId, 7L, null, 10_000L, NOW);
    }
}
