package com.hapbang.appearance.app;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.hapbang.appearance.domain.AppearanceCancelRequest;
import com.hapbang.appearance.domain.AppearanceCancelRequestStatus;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.out.AppearanceCancelRequestRepository;

import lombok.RequiredArgsConstructor;

/**
 * 출연 취소 요청 저장소 접근. 트랜잭션은 호출한 쪽이 연다.
 */
@Service
@RequiredArgsConstructor
public class AppearanceCancelRequestSupport {

    private final AppearanceCancelRequestRepository appearanceCancelRequestRepository;

    public AppearanceCancelRequest save(AppearanceCancelRequest cancelRequest) {
        return appearanceCancelRequestRepository.save(cancelRequest);
    }

    /** 이 출연에 보낸 취소 요청 수 */
    public int countByAppearance(Long appearanceId) {
        return appearanceCancelRequestRepository.countByAppearanceId(appearanceId);
    }

    /**
     * 이 출연의 처리 중인 취소 요청.
     *
     * @throws AppearanceException 없음({@code CANCEL_REQUEST_NOT_PENDING})
     */
    public AppearanceCancelRequest getPending(Long appearanceId) {
        Optional<AppearanceCancelRequest> found = appearanceCancelRequestRepository.findByAppearanceIdAndStatus(
                appearanceId, AppearanceCancelRequestStatus.REQUESTED);
        if (found.isEmpty()) {
            throw new AppearanceException(AppearanceErrorCode.CANCEL_REQUEST_NOT_PENDING);
        }
        return found.get();
    }

    /** 이 출연의 취소 요청 이력, 순번 순 */
    public List<AppearanceCancelRequest> findByAppearance(Long appearanceId) {
        return appearanceCancelRequestRepository.findByAppearanceIdOrderBySequence(appearanceId);
    }

    /** 자동 거절 배치: 처리 중인 취소 요청이 있고 방송 예정 일시가 지난 출연 ID (최대 {@code limit}건) */
    public List<Long> findDueAppearanceIds(LocalDateTime now, int limit) {
        return appearanceCancelRequestRepository.findAppearanceIdsDue(AppearanceCancelRequestStatus.REQUESTED, now,
                PageRequest.of(0, limit));
    }
}
