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

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 강의 검수 API (설계 8.2, 관리자). Swagger 명세는 {@link ApiV1LectureInspectionControllerDocs} */
@RestController
// 관리자 API는 /admin 경로(SecurityConfig)와 LectureInspectionFacade의 @PreAuthorize로 함께 막는다 (D16)
@RequestMapping("/api/v1/lectures/admin")
@RequiredArgsConstructor
public class ApiV1LectureInspectionController implements ApiV1LectureInspectionControllerDocs {

    private final LectureInspectionFacade lectureInspectionFacade;

    @Override
    @GetMapping("/inspections")
    public Page<InspectionLectureResponse> getInspections(
            @RequestParam(name = "status", defaultValue = "PENDING_INSPECTION") LectureStatus status,
            Pageable pageable) {
        return lectureInspectionFacade.getInspections(status, pageable);
    }

    @Override
    @PostMapping("/{lectureId}/inspection/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approve(@LoginUserId Long adminUserId, @PathVariable("lectureId") Long lectureId) {
        lectureInspectionFacade.approve(adminUserId, lectureId);
    }

    @Override
    @PostMapping("/{lectureId}/inspection/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(@LoginUserId Long adminUserId, @PathVariable("lectureId") Long lectureId,
            @Valid @RequestBody LectureRejectRequest request) {
        lectureInspectionFacade.reject(adminUserId, lectureId, request.reason());
    }
}
