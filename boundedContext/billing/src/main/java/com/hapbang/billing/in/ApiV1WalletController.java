package com.hapbang.billing.in;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.billing.app.WalletFacade;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.global.auth.LoginUserId;
import com.hapbang.shared.member.domain.Role;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

// 로그인한 회원 자신의 지갑만 다루는 지갑 API다.
@RestController
@RequestMapping("/api/v1/wallets")
@RequiredArgsConstructor
public class ApiV1WalletController {

    private final WalletFacade walletFacade;

    // 내 지갑 잔액을 조회하며, 지갑이 없으면 만들어 반환하고 관리자는 403으로 거절한다.
    @GetMapping("/me")
    public WalletResponse me(@LoginUserId Long userId, HttpServletRequest request) {
        // 세션의 역할로 판단한다 (Spring Security가 ROLE_ 접두사를 붙여 확인). 관리자에게는 지갑을 만들지 않는다.
        if (request.isUserInRole(Role.ADMIN.name())) {
            throw new BillingException(BillingErrorCode.WALLET_ACCESS_DENIED);
        }
        return WalletResponse.from(walletFacade.getOrCreateWallet(userId));
    }
}
