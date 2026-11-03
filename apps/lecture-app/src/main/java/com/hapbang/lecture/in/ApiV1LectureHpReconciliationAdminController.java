package com.hapbang.lecture.in;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.lecture.app.LectureHpReconciliationAdminFacade;
import com.hapbang.lecture.app.dto.LectureHpReconciliationRunResponse;
import com.hapbang.lecture.global.auth.LoginUserId;
import com.hapbang.lecture.global.response.RsData;

import lombok.RequiredArgsConstructor;

/** 강의 서비스 간 HP 대사 API (관리자, D74). Swagger 명세는 {@link ApiV1LectureHpReconciliationAdminSpec} */
// 성공은 global RsData로 감싸고, 오류는 global 공통 핸들러가 ProblemDetail로 응답한다 (D29).
@RestController
// 관리자 API는 /admin 경로(SecurityConfig)와 LectureHpReconciliationAdminFacade의 @PreAuthorize로 함께 막는다 (D16)
@RequestMapping("/api/v1/lectures/admin/hp-reconciliations")
@RequiredArgsConstructor
public class ApiV1LectureHpReconciliationAdminController implements ApiV1LectureHpReconciliationAdminSpec {

    private final LectureHpReconciliationAdminFacade lectureHpReconciliationAdminFacade;

    @Override
    @PostMapping
    public RsData<LectureHpReconciliationRunResponse> run(@LoginUserId Long adminUserId) {
        return RsData.ok("HP 대사를 실행했습니다.", lectureHpReconciliationAdminFacade.run(adminUserId));
    }

    @Override
    @GetMapping("/latest")
    public RsData<LectureHpReconciliationRunResponse> getLatest() {
        return RsData.ok("마지막 HP 대사 결과를 조회했습니다.", lectureHpReconciliationAdminFacade.getLatest());
    }
}
