package com.hapbang.appearance.app;

import java.util.Collection;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceRequest;
import com.hapbang.appearance.domain.AppearanceRequestStatus;
import com.hapbang.appearance.out.AppearanceRequestRepository;

import lombok.RequiredArgsConstructor;

/**
 * 출연 요청서 저장소 접근. UseCase는 저장소를 직접 쓰지 않고 이 클래스를 거친다. 트랜잭션은 호출한 쪽이 연다.
 */
@Service
@RequiredArgsConstructor
public class AppearanceRequestSupport {

    /** 목록은 최신순 고정. 클라이언트의 sort 값은 쓰지 않는다 */
    private static final Sort LATEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt", "id");

    private final AppearanceRequestRepository appearanceRequestRepository;

    /**
     * @throws AppearanceException 없는 요청서({@code APPEARANCE_REQUEST_NOT_FOUND})
     */
    public AppearanceRequest getById(Long requestId) {
        Optional<AppearanceRequest> found = appearanceRequestRepository.findById(requestId);
        if (found.isEmpty()) {
            throw new AppearanceException(AppearanceErrorCode.APPEARANCE_REQUEST_NOT_FOUND);
        }
        return found.get();
    }

    /** 같은 호스트·게스트 사이에 진행 중(HOLD_PENDING·REQUESTED)인 요청서가 있는지 */
    public boolean existsInProgress(Long hostId, Long guestId) {
        return appearanceRequestRepository.existsByHostIdAndGuestIdAndStatusIn(hostId, guestId,
                AppearanceRequestStatus.IN_PROGRESS);
    }

    /**
     * 저장하고 바로 DB에 반영한다. 진행 중 요청 제한(부분 유니크 인덱스)에 걸리면 여기서 예외가 난다.
     *
     * @throws org.springframework.dao.DataIntegrityViolationException DB 제약 위반
     */
    public AppearanceRequest saveNow(AppearanceRequest request) {
        return appearanceRequestRepository.saveAndFlush(request);
    }

    /** 수정 내용을 바로 DB에 반영한다 (응답의 수정 시각을 맞추기 위해) */
    public void flush() {
        appearanceRequestRepository.flush();
    }

    // TODO(검색·필터): 기간·키워드 조건이 정해지면 조건 객체(예: AppearanceRequestSearchCondition)를 받아
    //  Specification 등으로 조회 조건을 조합한다. 보이는 상태 제한과 최신순 정렬은 그대로 유지한다.

    /**
     * 보낸 요청 목록 (호스트). 최신순
     *
     * @param statuses 조회할 상태 (보이는 상태 중에서만, 호출한 쪽이 확인한다)
     */
    public Page<AppearanceRequest> findSent(Long hostId, Collection<AppearanceRequestStatus> statuses,
                                            Pageable pageable) {
        return appearanceRequestRepository.findByHostIdAndStatusIn(hostId, statuses, latestFirst(pageable));
    }

    /**
     * 받은 요청 목록 (게스트). 최신순
     *
     * @param statuses 조회할 상태 (보이는 상태 중에서만, 호출한 쪽이 확인한다)
     */
    public Page<AppearanceRequest> findReceived(Long guestId, Collection<AppearanceRequestStatus> statuses,
                                                Pageable pageable) {
        return appearanceRequestRepository.findByGuestIdAndStatusIn(guestId, statuses, latestFirst(pageable));
    }

    private Pageable latestFirst(Pageable pageable) {
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), LATEST_FIRST);
    }
}
