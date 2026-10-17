package com.hapbang.billing.in;

import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.billing.app.WalletFacade;
import com.hapbang.global.auth.LoginUserId;
import com.hapbang.global.response.RsData;

import lombok.RequiredArgsConstructor;

// 로그인한 회원 자신의 지갑만 다루는 지갑 API다. Swagger 명세는 ApiV1WalletSpec에 둔다.
// 성공은 global RsData로 감싸고, 오류는 global 공통 핸들러가 ProblemDetail로 응답한다.
@RestController
@RequestMapping("/api/v1/wallets")
@RequiredArgsConstructor
public class ApiV1WalletController implements ApiV1WalletSpec {

    private final WalletFacade walletFacade;

    // 내 지갑 잔액을 조회하며, 지갑이 없으면 만들어 반환한다. 역할 검사는 WalletFacade가 한다.
    @Override
    @GetMapping("/me")
    public RsData<WalletResponse> me(@LoginUserId Long userId) {
        return RsData.ok("내 지갑을 조회했습니다.", WalletResponse.from(walletFacade.getMyWallet(userId)));
    }

    // 내 HP 거래 내역을 작업 단위 최신순으로 조회한다 (#295). page·size 범위 검사는 명세(ApiV1WalletSpec)의 제약이 한다.
    @Override
    @GetMapping("/me/transactions")
    public RsData<Page<WalletTransactionResponse>> transactions(@LoginUserId Long userId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return RsData.ok("HP 거래 내역을 조회했습니다.",
                walletFacade.getMyTransactions(userId, page, size).map(WalletTransactionResponse::from));
    }
}
