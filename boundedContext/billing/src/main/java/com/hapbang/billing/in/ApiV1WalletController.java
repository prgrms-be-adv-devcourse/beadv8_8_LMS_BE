package com.hapbang.billing.in;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.billing.app.WalletFacade;
import com.hapbang.global.auth.LoginUserId;

import lombok.RequiredArgsConstructor;

// 로그인한 회원 자신의 지갑만 다루는 지갑 API다. Swagger 명세는 ApiV1WalletSpec에 둔다.
@RestController
@RequestMapping("/api/v1/wallets")
@RequiredArgsConstructor
public class ApiV1WalletController implements ApiV1WalletSpec {

    private final WalletFacade walletFacade;

    // 내 지갑 잔액을 조회하며, 지갑이 없으면 만들어 반환한다. 역할 검사는 WalletFacade가 한다.
    @Override
    @GetMapping("/me")
    public WalletResponse me(@LoginUserId Long userId) {
        return WalletResponse.from(walletFacade.getMyWallet(userId));
    }
}
