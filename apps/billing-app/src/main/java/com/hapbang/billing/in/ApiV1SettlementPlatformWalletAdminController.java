package com.hapbang.billing.in;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.billing.app.PlatformWalletAdminFacade;
import com.hapbang.billing.global.response.RsData;

import lombok.RequiredArgsConstructor;

// 관리자 플랫폼 지갑 조회 API (#336). /admin 경로(global SecurityConfig)와 PlatformWalletAdminFacade의 @PreAuthorize로 함께 막는다.
// Swagger 명세는 ApiV1SettlementPlatformWalletAdminSpec에 둔다.
@RestController
@RequestMapping("/api/v1/settlements/admin/platform-wallet")
@RequiredArgsConstructor
public class ApiV1SettlementPlatformWalletAdminController implements ApiV1SettlementPlatformWalletAdminSpec {

    private final PlatformWalletAdminFacade platformWalletAdminFacade;

    @Override
    @GetMapping
    public RsData<PlatformWalletResponse> getPlatformWallet() {
        return RsData.ok("플랫폼 지갑을 조회했습니다.", PlatformWalletResponse.from(platformWalletAdminFacade.get()));
    }

    @Override
    @GetMapping("/fee-incomes")
    public RsData<Page<FeeIncomeResponse>> getFeeIncomes(Pageable pageable) {
        return RsData.ok("수수료 수입 내역을 조회했습니다.",
                platformWalletAdminFacade.feeIncomes(pageable).map(FeeIncomeResponse::from));
    }
}
