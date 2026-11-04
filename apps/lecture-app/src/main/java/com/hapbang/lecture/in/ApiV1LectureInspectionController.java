package com.hapbang.lecture.in;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.lecture.global.auth.LoginUserId;
import com.hapbang.lecture.global.response.RsData;
import com.hapbang.lecture.app.LectureInspectionFacade;
import com.hapbang.lecture.app.dto.InspectionLectureResponse;
import com.hapbang.lecture.app.dto.LectureDetailResponse;
import com.hapbang.lecture.domain.LectureStatus;
import com.hapbang.lecture.in.dto.LectureRejectRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 강의 검수 API (설계 8.2, 관리자). Swagger 명세는 {@link ApiV1LectureInspectionSpec} */
// 성공은 global RsData로 감싸고(본문 없는 응답은 204), 오류는 global 공통 핸들러가 ProblemDetail로 응답한다 (D29).
@RestController
// 관리자 API는 /admin 경로(SecurityConfig)와 LectureInspectionFacade의 @PreAuthorize로 함께 막는다 (D16)
@RequestMapping("/api/v1/lectures/admin")
@RequiredArgsConstructor
public class ApiV1LectureInspectionController implements ApiV1LectureInspectionSpec {

    private final LectureInspectionFacade lectureInspectionFacade;

    @Override
    @GetMapping("/inspections")
    public RsData<Page<InspectionLectureResponse>> getInspections(
            @RequestParam(name = "status", defaultValue = "PENDING_INSPECTION") LectureStatus status,
            Pageable pageable) {
        return RsData.ok("검수 목록을 조회했습니다.", lectureInspectionFacade.getInspections(status, pageable));
    }

    @Override
    @GetMapping("/{lectureId}")
    public RsData<LectureDetailResponse> getLecture(@PathVariable("lectureId") Long lectureId) {
        return RsData.ok("강의를 조회했습니다.", lectureInspectionFacade.getLecture(lectureId));
    }

    @Override
    @PostMapping("/{lectureId}/inspection/approve")
    public ResponseEntity<Void> approve(@LoginUserId Long adminUserId, @PathVariable("lectureId") Long lectureId) {
        lectureInspectionFacade.approve(adminUserId, lectureId);
        return ResponseEntity.noContent().build();
    }

    @Override
    @PostMapping("/{lectureId}/inspection/reject")
    public ResponseEntity<Void> reject(@LoginUserId Long adminUserId, @PathVariable("lectureId") Long lectureId,
            @Valid @RequestBody LectureRejectRequest request) {
        lectureInspectionFacade.reject(adminUserId, lectureId, request.reason());
        return ResponseEntity.noContent().build();
    }
}
