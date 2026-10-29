package com.hapbang.lecture.in;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.lecture.app.LectureHpTaskAdminFacade;
import com.hapbang.lecture.app.dto.LectureHpTaskDetailResponse;
import com.hapbang.lecture.app.dto.LectureHpTaskResponse;
import com.hapbang.lecture.app.dto.LectureHpTaskSearch;
import com.hapbang.lecture.domain.LectureHpTaskStatus;
import com.hapbang.lecture.domain.LectureHpTaskType;
import com.hapbang.lecture.global.auth.LoginUserId;
import com.hapbang.lecture.global.response.RsData;
import com.hapbang.shared.billing.domain.BillingReferenceType;

import lombok.RequiredArgsConstructor;

/** 강의 HP 작업 관리 API (관리자, D57). Swagger 명세는 {@link ApiV1LectureHpTaskAdminSpec} */
// 성공은 global RsData로 감싸고(본문 없는 응답은 204), 오류는 global 공통 핸들러가 ProblemDetail로 응답한다 (D29).
@RestController
// 관리자 API는 /admin 경로(SecurityConfig)와 LectureHpTaskAdminFacade의 @PreAuthorize로 함께 막는다 (D16)
@RequestMapping("/api/v1/lectures/admin/hp-tasks")
@RequiredArgsConstructor
public class ApiV1LectureHpTaskAdminController implements ApiV1LectureHpTaskAdminSpec {

    private final LectureHpTaskAdminFacade lectureHpTaskAdminFacade;

    @Override
    @GetMapping
    public RsData<Page<LectureHpTaskResponse>> getHpTasks(
            @RequestParam(required = false) LectureHpTaskStatus status,
            @RequestParam(required = false) LectureHpTaskType taskType,
            @RequestParam(required = false) BillingReferenceType referenceType,
            @RequestParam(required = false) Long orderId,
            @RequestParam(required = false) Long orderItemId,
            @RequestParam(required = false) String errorCode,
            Pageable pageable) {
        LectureHpTaskSearch search = new LectureHpTaskSearch(status, taskType, referenceType, orderId, orderItemId,
                errorCode);
        return RsData.ok("HP 작업 목록을 조회했습니다.", lectureHpTaskAdminFacade.getHpTasks(search, pageable));
    }

    @Override
    @GetMapping("/{taskId}")
    public RsData<LectureHpTaskDetailResponse> getHpTask(@PathVariable("taskId") Long taskId) {
        return RsData.ok("HP 작업을 조회했습니다.", lectureHpTaskAdminFacade.getHpTask(taskId));
    }

    @Override
    @PostMapping("/{taskId}/retry")
    public ResponseEntity<Void> retry(@LoginUserId Long adminUserId, @PathVariable("taskId") Long taskId) {
        lectureHpTaskAdminFacade.retry(adminUserId, taskId);
        return ResponseEntity.noContent().build();
    }
}
