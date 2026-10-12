package com.hapbang.billing.in;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

// 지갑 API의 Swagger 명세이며, 매핑은 ApiV1WalletController가 맡는다.
@Tag(name = "지갑", description = "로그인한 회원 자신의 HP 지갑")
public interface ApiV1WalletControllerDocs {

    @Operation(summary = "내 지갑 조회",
            description = "유저·인플루언서·인증된 인플루언서. 충전·수익 HP의 가용·보류 잔액과 합계를 반환한다. "
                    + "지갑이 없으면 잔액 0인 지갑을 만들어 반환한다. 관리자는 403.")
    WalletResponse me(Long userId);
}
