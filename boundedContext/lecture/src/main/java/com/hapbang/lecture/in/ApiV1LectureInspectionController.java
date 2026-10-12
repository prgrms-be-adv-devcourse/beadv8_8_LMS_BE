package com.hapbang.lecture.in;

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
import com.hapbang.lecture.app.LectureInspectionService;
import com.hapbang.lecture.app.dto.InspectionLectureResponse;
import com.hapbang.lecture.app.dto.PageResponse;
import com.hapbang.lecture.domain.LectureStatus;
import com.hapbang.lecture.in.dto.LectureRejectRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/** 강의 검수 API (설계 8.2, 관리자) */
// TODO(security): 관리자만 허용한다(설계 9장). global SecurityConfig와 함께 추가한다.
@Tag(name = "강의 검수", description = "관리자의 강의 승인·반려")
@RestController
@RequestMapping("/api/v1/lectures")
@RequiredArgsConstructor
public class ApiV1LectureInspectionController {

    private final LectureInspectionService lectureInspectionService;

    @Operation(summary = "검수 목록", description = "관리자. 기본은 검수 대기(PENDING_INSPECTION), 오래된 신청부터.")
    @GetMapping("/inspections")
    public PageResponse<InspectionLectureResponse> getInspections(
            @RequestParam(name = "status", defaultValue = "PENDING_INSPECTION") LectureStatus status,
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
        return lectureInspectionService.getInspections(status, page, size);
    }

    @Operation(summary = "검수 승인",
            description = "관리자. 검수 대기 → 판매 중(ON_SALE). 검수 대기 중에는 콘텐츠가 바뀌지 않으므로 제출된 그대로 승인한다.")
    @PostMapping("/{lectureId}/inspection/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approve(@LoginUserId Long adminUserId, @PathVariable("lectureId") Long lectureId) {
        lectureInspectionService.approve(adminUserId, lectureId);
    }

    @Operation(summary = "검수 반려", description = "관리자. 검수 대기 → 반려(REJECTED). 사유 필수.")
    @PostMapping("/{lectureId}/inspection/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(@LoginUserId Long adminUserId, @PathVariable("lectureId") Long lectureId,
            @Valid @RequestBody LectureRejectRequest request) {
        lectureInspectionService.reject(adminUserId, lectureId, request.reason());
    }
}
