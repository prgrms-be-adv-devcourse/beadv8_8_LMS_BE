package com.hapbang.lecture.in;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.hapbang.lecture.app.dto.InspectionLectureResponse;
import com.hapbang.lecture.domain.LectureStatus;
import com.hapbang.lecture.in.dto.LectureRejectRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 강의 검수 API Swagger 명세 (설계 8.2, 관리자). 매핑은 {@link ApiV1LectureInspectionController} */
@Tag(name = "강의 검수", description = "관리자의 강의 승인·반려")
public interface ApiV1LectureInspectionControllerDocs {

    @Operation(summary = "검수 목록", description = "관리자. 기본은 검수 대기(PENDING_INSPECTION), 오래된 신청부터.")
    Page<InspectionLectureResponse> getInspections(LectureStatus status, Pageable pageable);

    @Operation(summary = "검수 승인",
            description = "관리자. 검수 대기 → 판매 중(ON_SALE). 검수 대기 중에는 콘텐츠가 바뀌지 않으므로 제출된 그대로 승인한다.")
    void approve(Long adminUserId, Long lectureId);

    @Operation(summary = "검수 반려", description = "관리자. 검수 대기 → 반려(REJECTED). 사유 필수.")
    void reject(Long adminUserId, Long lectureId, LectureRejectRequest request);
}
