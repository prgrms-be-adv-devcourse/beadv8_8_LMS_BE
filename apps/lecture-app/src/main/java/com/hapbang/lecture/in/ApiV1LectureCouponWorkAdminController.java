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

import com.hapbang.lecture.app.LectureCouponWorkAdminFacade;
import com.hapbang.lecture.app.dto.OrderCouponWorkDetailResponse;
import com.hapbang.lecture.app.dto.OrderCouponWorkFailResponse;
import com.hapbang.lecture.app.dto.OrderCouponWorkResponse;
import com.hapbang.lecture.domain.OrderCouponWorkKind;
import com.hapbang.lecture.domain.OrderCouponWorkStatus;
import com.hapbang.lecture.global.auth.LoginUserId;
import com.hapbang.lecture.global.response.RsData;

import lombok.RequiredArgsConstructor;

/** 강의 주문 쿠폰 작업 관리 API (관리자). Swagger 명세는 {@link ApiV1LectureCouponWorkAdminSpec} */
// 성공은 global RsData로 감싸고(본문 없는 응답은 204), 오류는 global 공통 핸들러가 ProblemDetail로 응답한다.
@RestController
// 관리자 API는 /admin 경로(SecurityConfig)와 LectureCouponWorkAdminFacade의 @PreAuthorize로 함께 막는다
@RequestMapping("/api/v1/lectures/admin/coupon-works")
@RequiredArgsConstructor
public class ApiV1LectureCouponWorkAdminController implements ApiV1LectureCouponWorkAdminSpec {

    private final LectureCouponWorkAdminFacade lectureCouponWorkAdminFacade;

    @Override
    @GetMapping
    public RsData<Page<OrderCouponWorkResponse>> getCouponWorks(
            @RequestParam(required = false) OrderCouponWorkStatus status,
            @RequestParam(required = false) OrderCouponWorkKind kind,
            Pageable pageable) {
        return RsData.ok("쿠폰 작업 목록을 조회했습니다.", lectureCouponWorkAdminFacade.getCouponWorks(status, kind, pageable));
    }

    @Override
    @GetMapping("/{workId}")
    public RsData<OrderCouponWorkDetailResponse> getCouponWork(@PathVariable("workId") String workId) {
        return RsData.ok("쿠폰 작업을 조회했습니다.", lectureCouponWorkAdminFacade.getCouponWork(workId));
    }

    @Override
    @PostMapping("/{workId}/retry")
    public ResponseEntity<Void> retry(@LoginUserId Long adminUserId, @PathVariable("workId") String workId) {
        lectureCouponWorkAdminFacade.retry(adminUserId, workId);
        return ResponseEntity.noContent().build();
    }

    @Override
    @PostMapping("/{workId}/fail")
    public RsData<OrderCouponWorkFailResponse> fail(@LoginUserId Long adminUserId,
                                                    @PathVariable("workId") String workId) {
        return RsData.ok("쿠폰 주문의 결제 실패를 확정했습니다.", lectureCouponWorkAdminFacade.fail(adminUserId, workId));
    }

    @Override
    @PostMapping("/{workId}/resolve")
    public ResponseEntity<Void> resolve(@LoginUserId Long adminUserId, @PathVariable("workId") String workId) {
        lectureCouponWorkAdminFacade.resolve(adminUserId, workId);
        return ResponseEntity.noContent().build();
    }
}
