package com.hapbang.appearance.app;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.hapbang.appearance.domain.Appearance;
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

    /**
     * 출연 요청서의 반환(거절·응답 전 취소·만료) 진행 상태. 반환할 일이 없으면 null.
     * 성립 뒤 합의 취소 반환은 출연 단위라 여기에 섞지 않는다 ({@link #findReleaseStatusOfAppearance}).
     */
    public HpTaskStatus findReleaseStatusOfRequest(Long requestId) {
        return statusOf(HpTask.requestId(HpTaskRefType.APPEARANCE_REQUEST, requestId, HpTaskType.RELEASE, 1));
    }

    /**
     * 출연 모집글의 빈자리 반환(마감 때 성립하지 못한 인원분, 또는 모집 중 전체 취소 때 빈자리) 진행 상태.
     * 반환할 일이 없으면(마감 전, 정원이 다 찬 채 마감) null. 모집글 하나에서 둘 중 하나만 생겨 같은 반환 요청 ID를 쓴다.
     * 마감 뒤 합의 취소로 생긴 출연별 반환은 섞지 않는다 (마감 반환이 실패했는데 다른 반환 결과로 가려지지 않도록).
     */
    public HpTaskStatus findReleaseStatusOfRecruit(Long recruitId) {
        return statusOf(HpTask.requestId(HpTaskRefType.APPEARANCE_RECRUIT, recruitId, HpTaskType.RELEASE, 1));
    }

    /**
     * 출연 하나의 반환(합의 취소 등) 진행 상태. 반환할 일이 없으면(성립 중, 모집글 마감 전 합의 취소) null.
     * 출연이 쓰는 보류(요청서 또는 모집글)에서 출연 단위 반환 요청 ID로 찾는다.
     */
    public HpTaskStatus findReleaseStatusOfAppearance(Appearance appearance) {
        if (appearance.isFromRecruit()) {
            return statusOf(HpTask.appearanceRequestId(HpTaskRefType.APPEARANCE_RECRUIT, appearance.getRecruitId(),
                    HpTaskType.RELEASE, appearance.getId()));
        }
        return statusOf(HpTask.appearanceRequestId(HpTaskRefType.APPEARANCE_REQUEST, appearance.getRequestId(),
                HpTaskType.RELEASE, appearance.getId()));
    }

    /** 출연 하나의 위약금 정산(호스트 일방 취소) 진행 상태. 일방 취소 전이면 null (#239) */
    public HpTaskStatus findPenaltyStatusOfAppearance(Appearance appearance) {
        return statusOf(penaltyRequestId(appearance));
    }

    /** 출연 하나의 위약금 정산 요청 ID (= Billing 취소 ID). 출연이 쓰는 보류(요청서·모집글) 단위로 만든다 */
    public static String penaltyRequestId(Appearance appearance) {
        if (appearance.isFromRecruit()) {
            return HpTask.appearanceRequestId(HpTaskRefType.APPEARANCE_RECRUIT, appearance.getRecruitId(),
                    HpTaskType.PENALTY, appearance.getId());
        }
        return HpTask.appearanceRequestId(HpTaskRefType.APPEARANCE_REQUEST, appearance.getRequestId(),
                HpTaskType.PENALTY, appearance.getId());
    }

    private HpTaskStatus statusOf(String idempotencyKey) {
        Optional<HpTask> found = hpTaskRepository.findByIdempotencyKey(idempotencyKey);
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
