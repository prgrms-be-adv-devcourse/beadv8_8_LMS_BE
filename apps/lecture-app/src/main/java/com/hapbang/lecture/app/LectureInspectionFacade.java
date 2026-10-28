package com.hapbang.lecture.app;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.lecture.app.dto.InspectionLectureResponse;
import com.hapbang.lecture.app.dto.LectureDetailResponse;
import com.hapbang.lecture.domain.LectureStatus;

import lombok.RequiredArgsConstructor;

/**
 * 관리자의 강의 검수 진입점 (설계 8.2).
 * 관리자 여부는 여기서 검사한다. /admin 경로는 SecurityConfig도 함께 막는다 (D16).
 * 로그인 정보가 없는 호출(배치·이벤트 리스너)은 거부된다.
 */
@Service
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class LectureInspectionFacade {

    private final LectureApproveUseCase lectureApproveUseCase;
    private final LectureRejectUseCase lectureRejectUseCase;
    private final LectureSupport lectureSupport;

    /** 검수 목록 (삭제된 강의 제외, 오래된 신청부터) */
    @Transactional(readOnly = true)
    public Page<InspectionLectureResponse> getInspections(LectureStatus status, Pageable pageable) {
        return lectureSupport.getInspections(status, pageable);
    }

    /** 검수용 강의 상세 (검수 대기·반려·삭제 포함, 반려 사유 포함) */
    @Transactional(readOnly = true)
    public LectureDetailResponse getLecture(Long lectureId) {
        return lectureSupport.getLectureForInspection(lectureId);
    }

    /** 검수 승인 */
    @Transactional
    public void approve(Long adminUserId, Long lectureId) {
        lectureApproveUseCase.approve(adminUserId, lectureId);
    }

    /** 검수 반려 */
    @Transactional
    public void reject(Long adminUserId, Long lectureId, String reason) {
        lectureRejectUseCase.reject(adminUserId, lectureId, reason);
    }
}
