package com.hapbang.coupon.in;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.coupon.app.CouponDltAdminFacade;
import com.hapbang.coupon.app.dto.CouponDltLogView;
import com.hapbang.coupon.domain.CouponDltKind;
import com.hapbang.coupon.domain.CouponDltStatus;
import com.hapbang.coupon.global.auth.LoginUserId;
import com.hapbang.coupon.global.response.RsData;
import com.hapbang.coupon.in.dto.CouponDltRejectRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 관리자 쿠폰 발급 DLT API. Swagger 명세는 {@link ApiV1CouponDltAdminSpec} */
@RestController
// 관리자 API는 /admin 경로(강의 앱 SecurityConfig)와 CouponDltAdminFacade의 @PreAuthorize로 함께 막는다
@RequestMapping("/api/v1/coupons/admin/dlt")
@RequiredArgsConstructor
public class ApiV1CouponDltAdminController implements ApiV1CouponDltAdminSpec {

    private final CouponDltAdminFacade couponDltAdminFacade;

    @Override
    @GetMapping
    public RsData<Page<CouponDltLogView>> list(@RequestParam(name = "status", required = false) CouponDltStatus status,
                                               @PageableDefault(sort = "id", direction = Sort.Direction.DESC)
                                               Pageable pageable) {
        return RsData.ok("DLT 격리 기록을 조회했습니다.", couponDltAdminFacade.list(status, pageable));
    }

    @Override
    @PostMapping("/{dltId}/replay")
    public RsData<CouponDltLogView> replay(@LoginUserId Long adminId, @PathVariable("dltId") Long dltId) {
        CouponDltLogView view = couponDltAdminFacade.replay(adminId, dltId);
        return RsData.ok(view.kind() == CouponDltKind.ORDER ? "주문 이벤트를 다시 처리했습니다." : "발급 요청을 다시 보냈습니다.",
                view);
    }

    @Override
    @PostMapping("/{dltId}/reject")
    public RsData<CouponDltLogView> reject(@LoginUserId Long adminId, @PathVariable("dltId") Long dltId,
                                           @Valid @RequestBody CouponDltRejectRequest request) {
        CouponDltLogView view = couponDltAdminFacade.reject(adminId, dltId, request.reason());
        return RsData.ok(view.kind() == CouponDltKind.ORDER ? "주문 이벤트를 영구 거절했습니다." : "발급 요청을 영구 거절했습니다.",
                view);
    }
}
