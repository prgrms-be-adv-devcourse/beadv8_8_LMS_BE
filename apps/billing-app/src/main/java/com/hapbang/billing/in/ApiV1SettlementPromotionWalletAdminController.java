package com.hapbang.billing.in;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.billing.app.PromotionWalletAdminFacade;
import com.hapbang.billing.global.auth.LoginUserId;
import com.hapbang.billing.global.response.RsData;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// 관리자 프로모션 지갑 조회·충전·회수 API. /admin 경로(global SecurityConfig)와 PromotionWalletAdminFacade의 @PreAuthorize로 함께 막는다.
// Swagger 명세는 ApiV1SettlementPromotionWalletAdminSpec에 둔다.
@RestController
@RequestMapping("/api/v1/settlements/admin/promotion-wallet")
@RequiredArgsConstructor
public class ApiV1SettlementPromotionWalletAdminController implements ApiV1SettlementPromotionWalletAdminSpec {

    private final PromotionWalletAdminFacade promotionWalletAdminFacade;

    @Override
    @GetMapping
    public RsData<PromotionWalletResponse> getPromotionWallet() {
        return RsData.ok("프로모션 지갑을 조회했습니다.", PromotionWalletResponse.from(promotionWalletAdminFacade.get()));
    }

    // 환전 신청처럼 재요청(replayed=true)도 201이다
    @Override
    @PostMapping("/fundings")
    @ResponseStatus(HttpStatus.CREATED)
    public RsData<PromotionFundingResponse> fund(@LoginUserId Long adminUserId,
            @Valid @RequestBody PromotionFundingRequest request) {
        return RsData.created("프로모션 지갑을 충전했습니다.",
                PromotionFundingResponse.from(promotionWalletAdminFacade.fund(adminUserId, request.toCommand())));
    }

    // 충전과 같이 재요청(replayed=true)도 201이다
    @Override
    @PostMapping("/withdrawals")
    @ResponseStatus(HttpStatus.CREATED)
    public RsData<PromotionWithdrawalResponse> withdraw(@LoginUserId Long adminUserId,
            @Valid @RequestBody PromotionWithdrawalRequest request) {
        return RsData.created("프로모션 지갑에서 회수했습니다.",
                PromotionWithdrawalResponse.from(promotionWalletAdminFacade.withdraw(adminUserId, request.toCommand())));
    }
}
