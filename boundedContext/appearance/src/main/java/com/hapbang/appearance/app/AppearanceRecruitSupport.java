package com.hapbang.appearance.app;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
        Optional<AppearanceRecruit> found = appearanceRecruitRepository.findById(recruitId);
        if (found.isEmpty()) {
            throw new AppearanceException(AppearanceErrorCode.APPEARANCE_RECRUIT_NOT_FOUND);
        }
        return found.get();
    }

    /** 저장하고 바로 DB에 반영한다 */
    public AppearanceRecruit saveNow(AppearanceRecruit recruit) {
        return appearanceRecruitRepository.saveAndFlush(recruit);
    }

    /** 수정 내용을 바로 DB에 반영한다 (응답의 수정 시각·버전을 맞추기 위해) */
    public void flush() {
        appearanceRecruitRepository.flush();
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
}
