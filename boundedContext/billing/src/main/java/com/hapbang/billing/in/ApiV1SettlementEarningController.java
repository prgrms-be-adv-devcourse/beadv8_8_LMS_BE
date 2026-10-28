package com.hapbang.billing.in;

import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.billing.app.EarningFacade;
import com.hapbang.billing.domain.EarningStatus;
import com.hapbang.global.auth.LoginUserId;
import com.hapbang.global.response.RsData;

import lombok.RequiredArgsConstructor;

// 로그인한 회원 자신의 지급 예정 수익(강의 판매·출연) 통합 조회 API다 (#295). 지급은 정산 영역이라 /api/v1/settlements 아래에 둔다.
// Swagger 명세는 ApiV1SettlementEarningSpec에 둔다. 역할 검사는 EarningFacade가, page·size 범위 검사는 명세의 제약이 한다.
@RestController
@RequestMapping("/api/v1/settlements/earnings")
@RequiredArgsConstructor
public class ApiV1SettlementEarningController implements ApiV1SettlementEarningSpec {

    private final EarningFacade earningFacade;

    @Override
    @GetMapping("/summary")
    public RsData<EarningSummaryResponse> summary(@LoginUserId Long userId) {
        return RsData.ok("지급 예정 수익 요약을 조회했습니다.", EarningSummaryResponse.from(earningFacade.summary(userId)));
    }

    @Override
    @GetMapping
    public RsData<Page<EarningItemResponse>> earnings(@LoginUserId Long userId,
            @RequestParam(name = "status", required = false) Set<EarningStatus> statuses,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return RsData.ok("지급 예정 수익 목록을 조회했습니다.",
                earningFacade.list(userId, statuses, page, size).map(EarningItemResponse::from));
    }
}
