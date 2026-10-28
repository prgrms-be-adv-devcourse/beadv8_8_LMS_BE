package com.hapbang.lecture.app;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.lecture.app.dto.PayoutFailureResponse;

import lombok.RequiredArgsConstructor;

/**
 * 관리자의 강의 지급 실패 확인·격리 해제 진입점 (D54).
 * 관리자 여부는 여기서 검사한다. /admin 경로는 SecurityConfig도 함께 막는다 (D16).
 */
@Service
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class LecturePayoutAdminFacade {

    private final LectureOrderSupport lectureOrderSupport;
    private final OrderPayoutReleaseUseCase orderPayoutReleaseUseCase;

    /** 지급 실패 기록이 있는 미지급 항목 (최근 실패순) */
    @Transactional(readOnly = true)
    public Page<PayoutFailureResponse> getPayoutFailures(Pageable pageable) {
        return lectureOrderSupport.getPayoutFailures(pageable);
    }

    /** 지급 격리 해제. 다음 지급 배치에서 다시 시도한다 */
    @Transactional
    public void releasePayoutQuarantine(Long adminUserId, Long orderItemId) {
        orderPayoutReleaseUseCase.release(adminUserId, orderItemId);
    }
}
