package com.hapbang.lecture.in;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.global.auth.LoginUserId;
import com.hapbang.lecture.app.LectureInspectionFacade;
import com.hapbang.lecture.app.dto.InspectionLectureResponse;
import com.hapbang.lecture.domain.LectureStatus;
import com.hapbang.lecture.in.dto.LectureRejectRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 강의 검수 API (설계 8.2, 관리자) */
@Tag(name = "강의 검수", description = "관리자의 강의 승인·반려")
@RestController
// 관리자 API는 /admin 경로(SecurityConfig)와 LectureInspectionFacade의 @PreAuthorize로 함께 막는다 (D15)
@RequestMapping("/api/v1/lectures/admin")
@RequiredArgsConstructor
public class ApiV1LectureInspectionController {

    private final LectureInspectionFacade lectureInspectionFacade;

    @Operation(summary = "검수 목록", description = "관리자. 기본은 검수 대기(PENDING_INSPECTION), 오래된 신청부터.")
    @GetMapping("/inspections")
    public Page<InspectionLectureResponse> getInspections(
            @RequestParam(name = "status", defaultValue = "PENDING_INSPECTION") LectureStatus status,
            Pageable pageable) {
        return lectureInspectionFacade.getInspections(status, pageable);
    }

    @Operation(summary = "검수 승인",
            description = "관리자. 검수 대기 → 판매 중(ON_SALE). 검수 대기 중에는 콘텐츠가 바뀌지 않으므로 제출된 그대로 승인한다.")
    @PostMapping("/{lectureId}/inspection/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approve(@LoginUserId Long adminUserId, @PathVariable("lectureId") Long lectureId) {
        lectureInspectionFacade.approve(adminUserId, lectureId);
    }

    @Operation(summary = "검수 반려", description = "관리자. 검수 대기 → 반려(REJECTED). 사유 필수.")
    @PostMapping("/{lectureId}/inspection/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(@LoginUserId Long adminUserId, @PathVariable("lectureId") Long lectureId,
            @Valid @RequestBody LectureRejectRequest request) {
        lectureInspectionFacade.reject(adminUserId, lectureId, request.reason());
    }
}
