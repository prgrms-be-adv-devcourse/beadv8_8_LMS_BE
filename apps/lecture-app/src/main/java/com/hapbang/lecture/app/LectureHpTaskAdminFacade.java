package com.hapbang.lecture.app;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.lecture.app.dto.LectureHpTaskDetailResponse;
import com.hapbang.lecture.app.dto.LectureHpTaskResponse;
import com.hapbang.lecture.app.dto.LectureHpTaskSearch;
import com.hapbang.lecture.domain.LectureHpTask;
import com.hapbang.lecture.out.LectureHpTaskAttemptRepository;

import lombok.RequiredArgsConstructor;

/**
 * 관리자의 강의 HP 작업 조회·재시도 진입점 (reference.md 8.2.1, D57 (9)).
 * 관리자 여부는 여기서 검사한다. /admin 경로는 SecurityConfig도 함께 막는다 (D16).
 */
@Service
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class LectureHpTaskAdminFacade {

    private final LectureHpTaskSupport lectureHpTaskSupport;
    private final LectureHpTaskAttemptRepository lectureHpTaskAttemptRepository;
    private final LectureHpTaskRetryUseCase lectureHpTaskRetryUseCase;

    /** 작업 목록 (최신순) */
    @Transactional(readOnly = true)
    public Page<LectureHpTaskResponse> getHpTasks(LectureHpTaskSearch search, Pageable pageable) {
        return lectureHpTaskSupport.search(search, pageable).map(LectureHpTaskResponse::of);
    }

    /** 작업 상세와 시도 이력 */
    @Transactional(readOnly = true)
    public LectureHpTaskDetailResponse getHpTask(Long taskId) {
        LectureHpTask task = lectureHpTaskSupport.getById(taskId);
        return LectureHpTaskDetailResponse.of(task,
                lectureHpTaskAttemptRepository.findByTaskIdOrderByAttemptNo(taskId));
    }

    /** {@code DEAD} 작업 재시도. 다음 재시도 배치가 같은 요청 ID로 다시 보낸다 */
    @Transactional
    public void retry(Long adminUserId, Long taskId) {
        lectureHpTaskRetryUseCase.retry(adminUserId, taskId);
    }
}
