package com.hapbang.lecture.app;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.config.LectureHpTaskProperties;
import com.hapbang.lecture.out.LectureHpTaskRepository;

/**
 * HP 작업 재시도 배치의 처리 (D57 (7)). 실행할 작업(다음 시도 시각이 지난 대기 작업, 선점이 만료된 작업)을
 * 다음 시도 시각 순으로 정한 수까지 골라 하나씩 실행한다. 트랜잭션 밖에서 실행한다(실행기가 선점·기록 트랜잭션을 연다).
 * 여러 실행기가 같은 작업을 골라도 선점은 하나만 성공한다.
 * <p>
 * 지갑 회로가 열려 있으면 작업을 고르지 않고, 회차 도중에 열리면 남은 작업은 다음 회차로 넘긴다(D79).
 * 회로를 회차 시작 때만 보지 않고 작업마다 보는 것은, 시험 중(HALF_OPEN)에 시험 호출이 실패하면 회차 도중에 다시 열리기 때문이다.
 */
@Service
public class LectureHpTaskBatchUseCase {

    private final LectureHpTaskRepository lectureHpTaskRepository;
    private final LectureHpTaskExecutor lectureHpTaskExecutor;
    private final LectureHpTaskProperties properties;
    private final Clock clock;

    public LectureHpTaskBatchUseCase(LectureHpTaskRepository lectureHpTaskRepository,
                                     LectureHpTaskExecutor lectureHpTaskExecutor, LectureHpTaskProperties properties,
                                     @Qualifier(LectureConfig.CLOCK) Clock clock) {
        this.lectureHpTaskRepository = lectureHpTaskRepository;
        this.lectureHpTaskExecutor = lectureHpTaskExecutor;
        this.properties = properties;
        this.clock = clock;
    }

    /** 설정한 수({@code lecture.hp-task.batch-size})까지 실행한다 */
    public int runDue() {
        return runDue(properties.batchSize());
    }

    /** @return 실행을 시도한 작업 수. 지갑 회로가 열려 있어 넘긴 작업은 세지 않는다 */
    public int runDue(int limit) {
        if (!lectureHpTaskExecutor.canCallBilling()) {
            return 0;
        }
        List<Long> taskIds = lectureHpTaskRepository.findRunnableIds(LocalDateTime.now(clock), PageRequest.of(0, limit));
        int executed = 0;
        for (Long taskId : taskIds) {
            if (!lectureHpTaskExecutor.canCallBilling()) {
                break;
            }
            lectureHpTaskExecutor.execute(taskId);
            executed++;
        }
        return executed;
    }
}
