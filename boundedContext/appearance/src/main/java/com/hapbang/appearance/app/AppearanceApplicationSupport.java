package com.hapbang.appearance.app;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import com.hapbang.appearance.domain.AppearanceApplication;
import com.hapbang.appearance.domain.AppearanceApplicationStatus;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.out.AppearanceApplicationRepository;
import com.hapbang.appearance.out.AppearanceApplicationRepository.RecruitApplicantCount;

import lombok.RequiredArgsConstructor;

/**
 * 출연 모집 지원 저장소 접근. UseCase는 저장소를 직접 쓰지 않고 이 클래스를 거친다. 트랜잭션은 호출한 쪽이 연다.
 */
@Service
@RequiredArgsConstructor
public class AppearanceApplicationSupport {

    /** 목록은 최신순 고정. 클라이언트의 sort 값은 쓰지 않는다 */
    private static final Sort LATEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt", "id");

    private final AppearanceApplicationRepository appearanceApplicationRepository;

    /**
     * @throws AppearanceException 없는 지원({@code APPLICATION_NOT_FOUND})
     */
    public AppearanceApplication getById(Long applicationId) {
        Optional<AppearanceApplication> found = appearanceApplicationRepository.findById(applicationId);
        if (found.isEmpty()) {
            throw new AppearanceException(AppearanceErrorCode.APPLICATION_NOT_FOUND);
        }
        return found.get();
    }

    /** 이 게스트가 이 모집글에 지원한 적이 있는지 (취소·거절된 지원 포함) */
    public boolean existsByRecruitAndGuest(Long recruitId, Long guestId) {
        return appearanceApplicationRepository.existsByRecruitIdAndGuestId(recruitId, guestId);
    }

    /**
     * 저장하고 바로 DB에 반영한다. 같은 게스트가 같은 모집글에 동시에 지원하면 유니크 제약에서 예외가 난다.
     *
     * @throws org.springframework.dao.DataIntegrityViolationException DB 제약 위반
     */
    public AppearanceApplication saveNow(AppearanceApplication application) {
        return appearanceApplicationRepository.saveAndFlush(application);
    }

    /**
     * 지원·모집글 변경을 바로 DB에 반영한다. 다른 요청이 먼저 바꿨으면(버전 충돌) 409로 바꿔 던진다.
     *
     * @throws AppearanceException 동시 처리 충돌({@code CONCURRENT_UPDATE})
     */
    public void flushOrConflict() {
        try {
            appearanceApplicationRepository.flush();
        } catch (ObjectOptimisticLockingFailureException exception) {
            throw new AppearanceException(AppearanceErrorCode.CONCURRENT_UPDATE);
        }
    }

    /** 모집글의 지원자 목록 (호스트). 최신순 */
    public Page<AppearanceApplication> findByRecruit(Long recruitId,
                                                     Collection<AppearanceApplicationStatus> statuses,
                                                     Pageable pageable) {
        return appearanceApplicationRepository.findByRecruitIdAndStatusIn(recruitId, statuses, latestFirst(pageable));
    }

    /** 내 지원 목록 (게스트). 최신순 */
    public Page<AppearanceApplication> findByGuest(Long guestId, Collection<AppearanceApplicationStatus> statuses,
                                                   Pageable pageable) {
        return appearanceApplicationRepository.findByGuestIdAndStatusIn(guestId, statuses, latestFirst(pageable));
    }

    /** 모집글 하나의 지원자 수: 취소한 지원만 뺀 전체 지원 */
    public long countApplicants(Long recruitId) {
        return appearanceApplicationRepository.countByRecruitIdAndStatusIn(recruitId,
                AppearanceApplicationStatus.COUNTED);
    }

    /** 모집글별 지원자 수 (목록 응답용). 지원이 없는 모집글은 결과에 없으므로 0으로 본다 */
    public Map<Long, Long> countApplicants(Collection<Long> recruitIds) {
        Map<Long, Long> counts = new HashMap<>();
        if (recruitIds.isEmpty()) {
            return counts;
        }
        for (RecruitApplicantCount row : appearanceApplicationRepository.countByRecruitIds(recruitIds,
                AppearanceApplicationStatus.COUNTED)) {
            counts.put(row.getRecruitId(), row.getApplicantCount());
        }
        return counts;
    }

    private Pageable latestFirst(Pageable pageable) {
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), LATEST_FIRST);
    }
}
