package com.hapbang.lecture.app;

import static org.assertj.core.api.Assertions.*;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.LectureHpTask;
import com.hapbang.lecture.domain.LectureHpTaskPurpose;
import com.hapbang.lecture.domain.LectureHpTaskStatus;
import com.hapbang.lecture.fake.FakeBillingApiClient;
import com.hapbang.lecture.fake.RecordingCancelResultHandler;
import com.hapbang.lecture.global.scheduling.ConditionalOnSchedulingEnabled;
import com.hapbang.lecture.out.LectureHpTaskRepository;
import com.hapbang.testsupport.IntegrationTest;

// HP 작업 재시도 배치: 실행할 작업만 한 번에 정한 수까지 실행한다 (D57 (7))
@IntegrationTest
class LectureHpTaskBatchUseCaseTest {

    @Autowired
    private LectureHpTaskBatchUseCase batchUseCase;

    @Autowired
    private LectureHpTaskSupport lectureHpTaskSupport;

    @Autowired
    private LectureHpTaskRepository lectureHpTaskRepository;

    @Autowired
    private FakeBillingApiClient billing;

    // 실행기만 확인하도록 운영 CANCEL 처리기 자리에 결과만 남기는 처리기를 넣는다
    @TestBean(methodName = RecordingCancelResultHandler.FACTORY)
    private CancelResultHandler cancelResultHandler;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    @Qualifier(LectureConfig.CLOCK)
    private Clock clock;

    @BeforeEach
    void setUp() {
        billing.reset();
        cancelHandler().reset();
    }

    @Test
    void 배치는_실행할_작업만_한_번에_정한_수까지_실행한다() {
        Long due1 = register("LECTURE-CANCEL-31");
        Long due2 = register("LECTURE-CANCEL-32");
        Long notYet = register("LECTURE-CANCEL-33");
        jdbcTemplate.update("update lectures.lecture_hp_task set next_attempt_at = ? where id = ?",
                LocalDateTime.now(clock).plusMinutes(5), notYet);

        assertThat(batchUseCase.runDue(1)).isEqualTo(1);
        assertThat(batchUseCase.runDue(100)).isEqualTo(1);

        assertThat(status(due1)).isEqualTo(LectureHpTaskStatus.COMPLETED);
        assertThat(status(due2)).isEqualTo(LectureHpTaskStatus.COMPLETED);
        assertThat(status(notYet)).isEqualTo(LectureHpTaskStatus.PENDING);
        assertThat(billing.calls()).hasSize(2);
    }

    @Test
    void 배치는_스케줄링을_켰을_때만_1분마다_돈다() throws NoSuchMethodException {
        assertThat(LectureHpTaskBatch.class).hasAnnotation(ConditionalOnSchedulingEnabled.class);
        Method run = LectureHpTaskBatch.class.getDeclaredMethod("run");
        Scheduled scheduled = run.getAnnotation(Scheduled.class);
        assertThat(scheduled.cron()).isEqualTo("${lecture.hp-task.retry-cron:0 * * * * *}");
        assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
    }

    private Long register(String requestId) {
        LectureHpTask task = LectureHpTask.create(LectureHpTaskPurpose.CANCEL, requestId, 3L,
                Long.valueOf(requestId.substring(requestId.lastIndexOf('-') + 1)), 7L, null, 10_000L,
                LocalDateTime.now(clock));
        return transactionTemplate.execute(status -> lectureHpTaskSupport.register(task).getId());
    }

    private LectureHpTaskStatus status(Long id) {
        return lectureHpTaskRepository.findById(id).orElseThrow().getStatus();
    }

    private RecordingCancelResultHandler cancelHandler() {
        return (RecordingCancelResultHandler) cancelResultHandler;
    }
}
