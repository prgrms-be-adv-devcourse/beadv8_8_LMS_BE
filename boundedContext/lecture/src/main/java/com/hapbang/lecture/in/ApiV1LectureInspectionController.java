package com.hapbang.lecture.in;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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
import com.hapbang.lecture.domain.LectureStatus;
import com.hapbang.lecture.in.dto.LectureRejectRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 강의 검수 API (설계 8.2, 관리자). Swagger 명세는 {@link ApiV1LectureInspectionControllerDocs} */
@RestController
// 관리자 API는 /admin 경로(SecurityConfig)와 @PreAuthorize로 함께 막는다 (2026-10-12 회의)
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/lectures/admin")
@RequiredArgsConstructor
public class ApiV1LectureInspectionController implements ApiV1LectureInspectionControllerDocs {

    private final LectureInspectionService lectureInspectionService;

    @Override
    @GetMapping("/inspections")
    public Page<InspectionLectureResponse> getInspections(
            @RequestParam(name = "status", defaultValue = "PENDING_INSPECTION") LectureStatus status,
            Pageable pageable) {
        return lectureInspectionService.getInspections(status, pageable);
    }

    @Override
    @PostMapping("/{lectureId}/inspection/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approve(@LoginUserId Long adminUserId, @PathVariable("lectureId") Long lectureId) {
        lectureInspectionService.approve(adminUserId, lectureId);
    }

    @Override
    @PostMapping("/{lectureId}/inspection/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(@LoginUserId Long adminUserId, @PathVariable("lectureId") Long lectureId,
            @Valid @RequestBody LectureRejectRequest request) {
        lectureInspectionService.reject(adminUserId, lectureId, request.reason());
    }
}
