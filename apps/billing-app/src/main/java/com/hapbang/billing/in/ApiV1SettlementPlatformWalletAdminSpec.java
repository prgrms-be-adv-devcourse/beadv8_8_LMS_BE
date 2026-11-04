package com.hapbang.billing.in;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.hapbang.billing.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

// 관리자 플랫폼 지갑 조회 API의 Swagger 명세이며, 매핑은 ApiV1SettlementPlatformWalletAdminController가 맡는다 (#336).
@Tag(name = "플랫폼 지갑(관리자)", description = "환전 수수료 수입이 쌓이는 플랫폼 지갑의 잔액과 내역을 조회한다")
public interface ApiV1SettlementPlatformWalletAdminSpec {

    @Operation(summary = "플랫폼 지갑 수수료 수입 잔액",
            description = "관리자. 송금 완료된 환전의 수수료 합이다. 환전 송금이 성공할 때 기록되고, 최종 실패·취소·거절된 환전은 "
                    + "들어가지 않는다. 비로그인 401, 관리자가 아니면 403.")
    RsData<PlatformWalletResponse> getPlatformWallet();

    @Operation(summary = "플랫폼 지갑 수수료 수입 내역",
            description = "관리자. 환전 한 건에 한 줄이며 기록 시각 최신순 페이지(최대 100)다. 정렬은 바꿀 수 없다. "
                    + "신청 금액 = 송금액 + 수수료. 비로그인 401, 관리자가 아니면 403.")
    RsData<Page<FeeIncomeResponse>> getFeeIncomes(Pageable pageable);
}
