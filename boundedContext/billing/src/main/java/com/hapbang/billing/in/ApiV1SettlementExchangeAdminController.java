package com.hapbang.billing.in;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.billing.app.ExchangeAdminFacade;
import com.hapbang.billing.domain.ExchangeStatus;
import com.hapbang.global.auth.LoginUserId;
import com.hapbang.global.response.RsData;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// 관리자 환전 조회·승인·거절 API (#294). 관리자 API는 /admin 경로(global SecurityConfig)와 ExchangeAdminFacade의 @PreAuthorize로 함께 막는다.
// Swagger 명세는 ApiV1SettlementExchangeAdminSpec에 둔다.
@RestController
@RequestMapping("/api/v1/settlements/admin/exchanges")
@RequiredArgsConstructor
public class ApiV1SettlementExchangeAdminController implements ApiV1SettlementExchangeAdminSpec {

    private final ExchangeAdminFacade exchangeAdminFacade;

    @Override
    @GetMapping
    public RsData<Page<AdminExchangeResponse>> getExchanges(
            @RequestParam(name = "status", defaultValue = "REQUESTED") ExchangeStatus status, Pageable pageable) {
        return RsData.ok("환전 신청 목록을 조회했습니다.",
                exchangeAdminFacade.list(status, pageable).map(AdminExchangeResponse::from));
    }

    @Override
    @GetMapping("/{exchangeId}")
    public RsData<AdminExchangeResponse> getExchange(@PathVariable("exchangeId") Long exchangeId) {
        return RsData.ok("환전 신청을 조회했습니다.", AdminExchangeResponse.from(exchangeAdminFacade.get(exchangeId)));
    }

    @Override
    @PostMapping("/{exchangeId}/approve")
    public RsData<AdminExchangeResponse> approve(@LoginUserId Long adminId,
            @PathVariable("exchangeId") Long exchangeId) {
        return RsData.ok("환전 신청을 승인했습니다.",
                AdminExchangeResponse.from(exchangeAdminFacade.approve(adminId, exchangeId)));
    }

    @Override
    @PostMapping("/{exchangeId}/reject")
    public RsData<AdminExchangeResponse> reject(@LoginUserId Long adminId,
            @PathVariable("exchangeId") Long exchangeId, @Valid @RequestBody ExchangeRejectRequest request) {
        return RsData.ok("환전 신청을 거절했습니다.",
                AdminExchangeResponse.from(exchangeAdminFacade.reject(adminId, exchangeId, request.rejectionReason())));
    }
}
