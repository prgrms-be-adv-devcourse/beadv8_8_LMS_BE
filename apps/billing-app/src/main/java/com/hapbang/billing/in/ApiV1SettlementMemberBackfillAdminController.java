package com.hapbang.billing.in;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.billing.app.BillingUserBackfillFacade;
import com.hapbang.billing.global.response.RsData;

import lombok.RequiredArgsConstructor;

// 관리자 수동 기존 회원 BillingUser Backfill API (#324). 관리자 API는 /admin 경로(global SecurityConfig)와
// BillingUserBackfillFacade의 @PreAuthorize로 함께 막는다. afterId·limit 범위 검사는 명세(ApiV1SettlementMemberBackfillAdminSpec)의 제약이 한다.
// limit을 생략하면 관리자 HTTP 요청 시간을 줄이려고 100명만 처리한다(최대 5,000은 명시해서 쓴다).
@RestController
@RequestMapping("/api/v1/settlements/admin/billing-users")
@RequiredArgsConstructor
public class ApiV1SettlementMemberBackfillAdminController implements ApiV1SettlementMemberBackfillAdminSpec {

    private final BillingUserBackfillFacade billingUserBackfillFacade;

    @Override
    @PostMapping("/backfill")
    public RsData<BillingUserBackfillResponse> backfill(
            @RequestParam(name = "afterId", defaultValue = "0") long afterId,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return RsData.ok("회원 정보 Backfill을 실행했습니다.",
                BillingUserBackfillResponse.from(billingUserBackfillFacade.backfill(afterId, limit)));
    }
}
