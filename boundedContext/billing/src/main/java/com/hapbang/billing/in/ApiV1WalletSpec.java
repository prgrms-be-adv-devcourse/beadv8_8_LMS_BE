package com.hapbang.billing.in;

import com.hapbang.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

// 지갑 API의 Swagger 명세이며, 매핑은 ApiV1WalletController가 맡는다.
@Tag(name = "지갑", description = "로그인한 회원 자신의 HP 지갑")
public interface ApiV1WalletSpec {

    @Operation(summary = "내 지갑 조회",
            description = "유저·인플루언서·인증된 인플루언서. 충전·수익 HP의 가용·보류 잔액과 합계를 반환한다. "
                    + "지갑이 없으면 잔액 0인 지갑을 만들어 반환한다(만들어도 200). 응답은 RsData의 data에 담는다. "
                    + "비로그인 401, 관리자 403(ProblemDetail).")
    RsData<WalletResponse> me(Long userId);
}
