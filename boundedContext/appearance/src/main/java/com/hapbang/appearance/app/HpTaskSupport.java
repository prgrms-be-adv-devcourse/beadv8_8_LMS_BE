package com.hapbang.appearance.app;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.hapbang.appearance.domain.HpTask;
import com.hapbang.appearance.domain.HpTaskRefType;
import com.hapbang.appearance.domain.HpTaskStatus;
import com.hapbang.appearance.domain.HpTaskType;
import com.hapbang.appearance.out.HpTaskRepository;

import lombok.RequiredArgsConstructor;

/**
 * HP 작업 저장소 접근. 트랜잭션은 호출한 쪽이 연다.
 */
@Service
@RequiredArgsConstructor
public class HpTaskSupport {

    private final HpTaskRepository hpTaskRepository;

    public HpTask save(HpTask task) {
        return hpTaskRepository.save(task);
    }

    public HpTask getById(Long taskId) {
        Optional<HpTask> found = hpTaskRepository.findById(taskId);
        if (found.isEmpty()) {
            throw new IllegalStateException("HP 작업이 없습니다: " + taskId);
        }
        return found.get();
    }

    /** 출연 요청서의 반환 진행 상태. 반환할 일이 없으면(거절·취소 전) null */
    public HpTaskStatus findReleaseStatusOfRequest(Long requestId) {
        Optional<HpTask> found = hpTaskRepository.findFirstByRefTypeAndRefIdAndTaskTypeOrderByIdDesc(
                HpTaskRefType.APPEARANCE_REQUEST, requestId, HpTaskType.RELEASE);
        if (found.isEmpty()) {
            return null;
        }
        return found.get().getStatus();
    }

    /** 출연 모집글의 반환 진행 상태 (가장 최근 반환 작업). 반환할 일이 없으면(마감 전, 정원이 다 찬 채 마감) null */
    public HpTaskStatus findReleaseStatusOfRecruit(Long recruitId) {
        Optional<HpTask> found = hpTaskRepository.findFirstByRefTypeAndRefIdAndTaskTypeOrderByIdDesc(
                HpTaskRefType.APPEARANCE_RECRUIT, recruitId, HpTaskType.RELEASE);
        if (found.isEmpty()) {
            return null;
        }
        return found.get().getStatus();
    }

    /** 재시도 배치가 실행할 작업 ID (최대 {@code limit}건) */
    public List<Long> findRunnableIds(LocalDateTime now, LocalDateTime graceCutoff, int limit) {
        return hpTaskRepository.findRunnableIds(now, graceCutoff, PageRequest.of(0, limit));
    }

    /** 상태별 작업 수 (지표용) */
    public long countByStatus(HpTaskStatus status) {
        return hpTaskRepository.countByStatus(status);
    }
}
