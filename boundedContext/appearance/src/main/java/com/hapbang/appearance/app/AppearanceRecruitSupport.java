package com.hapbang.appearance.app;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceRecruit;
import com.hapbang.appearance.domain.AppearanceRecruitStatus;
import com.hapbang.appearance.out.AppearanceRecruitRepository;

import lombok.RequiredArgsConstructor;

/**
 * 출연 모집글 저장소 접근. UseCase는 저장소를 직접 쓰지 않고 이 클래스를 거친다. 트랜잭션은 호출한 쪽이 연다.
 */
@Service
@RequiredArgsConstructor
public class AppearanceRecruitSupport {

    /** 목록은 최신순 고정. 클라이언트의 sort 값은 쓰지 않는다 */
    private static final Sort LATEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt", "id");

    private final AppearanceRecruitRepository appearanceRecruitRepository;

    /**
     * @throws AppearanceException 없는 모집글({@code APPEARANCE_RECRUIT_NOT_FOUND})
     */
    public AppearanceRecruit getById(Long recruitId) {
        return found(appearanceRecruitRepository.findById(recruitId));
    }

    /**
     * 지원할 모집글을 공유 잠금으로 읽는다. 지원 트랜잭션이 끝날 때까지 마감이 기다리게 해서,
     * 마감이 방금 들어온 지원을 놓치고 대기 지원으로 남기지 않도록 한다 (ISSUE.md APR-28 C9).
     *
     * @throws AppearanceException 없는 모집글({@code APPEARANCE_RECRUIT_NOT_FOUND})
     */
    public AppearanceRecruit getForApply(Long recruitId) {
        return found(appearanceRecruitRepository.findByIdForShare(recruitId));
    }

    /**
     * 마감·수락할 모집글을 배타 잠금으로 읽는다 (C9).
     * <ul>
     *     <li>마감: 진행 중인 지원·수락이 커밋된 뒤의 최신 상태·성립 인원으로 마감하고, 그동안 새 지원은 기다렸다가 마감된 모집글을 본다.</li>
     *     <li>잠그는 순서를 모집글 → 지원으로 맞춘다. 마감과 수락이 둘 다 모집글을 먼저 잠가야 서로 상대가 잡은 행을 기다리는
     *     교착 상태(deadlock)가 생기지 않는다.</li>
     * </ul>
     *
     * @throws AppearanceException 없는 모집글({@code APPEARANCE_RECRUIT_NOT_FOUND})
     */
    public AppearanceRecruit getForUpdate(Long recruitId) {
        return found(appearanceRecruitRepository.findByIdForUpdate(recruitId));
    }

    private AppearanceRecruit found(Optional<AppearanceRecruit> recruit) {
        if (recruit.isEmpty()) {
            throw new AppearanceException(AppearanceErrorCode.APPEARANCE_RECRUIT_NOT_FOUND);
        }
        return recruit.get();
    }

    /** 저장하고 바로 DB에 반영한다 */
    public AppearanceRecruit saveNow(AppearanceRecruit recruit) {
        return appearanceRecruitRepository.saveAndFlush(recruit);
    }

    /** 수정 내용을 바로 DB에 반영한다 (응답의 수정 시각·버전을 맞추기 위해) */
    public void flush() {
        appearanceRecruitRepository.flush();
    }

    /**
     * 모집글·지원 변경을 바로 DB에 반영한다. 다른 요청이 먼저 바꿨으면(버전 충돌) 409로 바꿔 던진다.
     *
     * @throws AppearanceException 동시 처리 충돌({@code CONCURRENT_UPDATE})
     */
    public void flushOrConflict() {
        try {
            appearanceRecruitRepository.flush();
        } catch (ObjectOptimisticLockingFailureException exception) {
            throw new AppearanceException(AppearanceErrorCode.CONCURRENT_UPDATE);
        }
    }

    // TODO(검색·필터): 플랫폼·카테고리·기간 등 조건이 정해지면 조건 객체(예: AppearanceRecruitSearchCondition)를 받아
    //  조회 조건을 조합한다. 보이는 상태 제한과 최신순 정렬은 그대로 유지한다.

    /**
     * 공개 목록. 모집 중이고 모집 마감 일시가 아직 지나지 않은 모집글, 최신순.
     * 마감 처리 전이어도 마감 일시가 지난 모집글은 현재 시각으로 걸러 낸다.
     */
    public Page<AppearanceRecruit> findOpen(LocalDateTime now, Pageable pageable) {
        return appearanceRecruitRepository.findByStatusAndRecruitDeadlineAfter(AppearanceRecruitStatus.RECRUITING,
                now, latestFirst(pageable));
    }

    /**
     * 내가 올린 모집글 목록 (호스트). 최신순
     *
     * @param statuses 조회할 상태 (보이는 상태 중에서만, 호출한 쪽이 확인한다)
     */
    public Page<AppearanceRecruit> findByHost(Long hostId, Collection<AppearanceRecruitStatus> statuses,
                                              Pageable pageable) {
        return appearanceRecruitRepository.findByHostIdAndStatusIn(hostId, statuses, latestFirst(pageable));
    }

    private Pageable latestFirst(Pageable pageable) {
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), LATEST_FIRST);
    }

    /** 기한 마감 배치: 모집 중이고 마감 일시가 지난 모집글 ID (최대 {@code limit}건) */
    public List<Long> findDeadlinePassedIds(LocalDateTime now, int limit) {
        return appearanceRecruitRepository.findIdsByStatusAndDeadlinePassed(AppearanceRecruitStatus.RECRUITING, now,
                PageRequest.of(0, limit));
    }

    /** 보류 재확인 배치: 기준 시각 이전에 만들어져 아직 보류 처리 중인 모집글 ID (최대 {@code limit}건) */
    public List<Long> findStaleHoldPendingIds(LocalDateTime cutoff, int limit) {
        return appearanceRecruitRepository.findIdsByStatusAndCreatedBefore(AppearanceRecruitStatus.HOLD_PENDING,
                cutoff, PageRequest.of(0, limit));
    }
}
