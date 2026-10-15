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

import com.hapbang.appearance.domain.Appearance;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceStatus;
import com.hapbang.appearance.out.AppearanceRepository;

import lombok.RequiredArgsConstructor;

/**
 * 출연 저장소 접근. 트랜잭션은 호출한 쪽이 연다.
 */
@Service
@RequiredArgsConstructor
public class AppearanceSupport {

    /** 목록은 최신순 고정. 클라이언트의 sort 값은 쓰지 않는다 */
    private static final Sort LATEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt", "id");

    private final AppearanceRepository appearanceRepository;

    /**
     * 저장하고 바로 DB에 반영한다. 요청서 하나에 출연 하나(유니크) 제약에 걸리면 여기서 예외가 난다.
     */
    public Appearance saveNow(Appearance appearance) {
        return appearanceRepository.saveAndFlush(appearance);
    }

    /** 출연 요청서에서 성립한 출연 ID. 아직 성립하지 않았으면 null */
    public Long findIdByRequestId(Long requestId) {
        Optional<Appearance> found = appearanceRepository.findByRequestId(requestId);
        if (found.isEmpty()) {
            return null;
        }
        return found.get().getId();
    }

    /** 모집 지원 ID별 성립한 출연 ID. 성립하지 않은 지원은 결과에 없다 */
    public Map<Long, Long> findIdsByApplicationIds(Collection<Long> applicationIds) {
        Map<Long, Long> appearanceIds = new HashMap<>();
        if (applicationIds.isEmpty()) {
            return appearanceIds;
        }
        for (Appearance appearance : appearanceRepository.findByApplicationIdIn(applicationIds)) {
            appearanceIds.put(appearance.getApplicationId(), appearance.getId());
        }
        return appearanceIds;
    }

    /**
     * @throws AppearanceException 없는 출연({@code APPEARANCE_NOT_FOUND})
     */
    public Appearance getById(Long appearanceId) {
        return found(appearanceRepository.findById(appearanceId));
    }

    /**
     * 출연을 배타 잠금으로 읽는다. 같은 출연의 취소 요청·응답·자동 거절이 차례로 처리돼, 나중 처리는 바뀐 상태를 본다.
     *
     * @throws AppearanceException 없는 출연({@code APPEARANCE_NOT_FOUND})
     */
    public Appearance getForUpdate(Long appearanceId) {
        return found(appearanceRepository.findByIdForUpdate(appearanceId));
    }

    /**
     * 출연의 출처 모집글 ID (요청에서 성립했으면 null). 출연을 영속성 컨텍스트에 올리지 않아, 뒤이어 잠금 조회한 출연이 최신 값이 된다.
     *
     * @throws AppearanceException 없는 출연({@code APPEARANCE_NOT_FOUND})
     */
    public Long findRecruitIdOf(Long appearanceId) {
        Optional<AppearanceRepository.SourceRef> found = appearanceRepository.findSourceRefById(appearanceId);
        if (found.isEmpty()) {
            throw new AppearanceException(AppearanceErrorCode.APPEARANCE_NOT_FOUND);
        }
        return found.get().getRecruitId();
    }

    /**
     * 출연 변경을 바로 DB에 반영한다. 다른 요청이 먼저 바꿨으면(버전 충돌) 409로 바꿔 던진다.
     *
     * @throws AppearanceException 동시 처리 충돌({@code CONCURRENT_UPDATE})
     */
    public void flushOrConflict() {
        try {
            appearanceRepository.flush();
        } catch (ObjectOptimisticLockingFailureException exception) {
            throw new AppearanceException(AppearanceErrorCode.CONCURRENT_UPDATE);
        }
    }

    /** 내가 호스트인 출연 목록. 최신순 */
    public Page<Appearance> findByHost(Long hostId, Collection<AppearanceStatus> statuses, Pageable pageable) {
        return appearanceRepository.findByHostIdAndStatusIn(hostId, statuses, latestFirst(pageable));
    }

    /** 내가 게스트인 출연 목록. 최신순 */
    public Page<Appearance> findByGuest(Long guestId, Collection<AppearanceStatus> statuses, Pageable pageable) {
        return appearanceRepository.findByGuestIdAndStatusIn(guestId, statuses, latestFirst(pageable));
    }

    private Pageable latestFirst(Pageable pageable) {
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), LATEST_FIRST);
    }

    private Appearance found(Optional<Appearance> appearance) {
        if (appearance.isEmpty()) {
            throw new AppearanceException(AppearanceErrorCode.APPEARANCE_NOT_FOUND);
        }
        return appearance.get();
    }
}
