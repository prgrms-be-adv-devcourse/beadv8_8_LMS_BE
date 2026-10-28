package com.hapbang.lecture.in;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.lecture.global.auth.LoginUserId;
import com.hapbang.lecture.global.response.RsData;
import com.hapbang.lecture.app.LecturePayoutAdminFacade;
import com.hapbang.lecture.app.dto.PayoutFailureResponse;

import lombok.RequiredArgsConstructor;

/** 강의 지급 실패 관리 API (관리자, D54). Swagger 명세는 {@link ApiV1LecturePayoutAdminSpec} */
// 성공은 global RsData로 감싸고(본문 없는 응답은 204), 오류는 global 공통 핸들러가 ProblemDetail로 응답한다 (D29).
@RestController
// 관리자 API는 /admin 경로(SecurityConfig)와 LecturePayoutAdminFacade의 @PreAuthorize로 함께 막는다 (D16)
@RequestMapping("/api/v1/lectures/admin/payout-failures")
@RequiredArgsConstructor
public class ApiV1LecturePayoutAdminController implements ApiV1LecturePayoutAdminSpec {

    private final LecturePayoutAdminFacade lecturePayoutAdminFacade;

    @Override
    @GetMapping
    public RsData<Page<PayoutFailureResponse>> getPayoutFailures(Pageable pageable) {
        return RsData.ok("지급 실패 목록을 조회했습니다.", lecturePayoutAdminFacade.getPayoutFailures(pageable));
    }

    @Override
    @PostMapping("/{orderItemId}/retry")
    public ResponseEntity<Void> releasePayoutQuarantine(@LoginUserId Long adminUserId,
            @PathVariable("orderItemId") Long orderItemId) {
        lecturePayoutAdminFacade.releasePayoutQuarantine(adminUserId, orderItemId);
        return ResponseEntity.noContent().build();
    }
}
