package com.hapbang.billing.in;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.billing.app.ExchangeFacade;
import com.hapbang.global.auth.LoginUserId;
import com.hapbang.global.response.RsData;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// 로그인한 회원 자신의 수익 HP 환전 신청 API다 (#285). 환전은 정산 영역이라 /api/v1/settlements 아래에 둔다(루트 AGENTS.md 3장).
// Swagger 명세는 ApiV1SettlementExchangeSpec에 둔다. 역할 검사는 ExchangeFacade가 한다.
@RestController
@RequestMapping("/api/v1/settlements/exchanges")
@RequiredArgsConstructor
public class ApiV1SettlementExchangeController implements ApiV1SettlementExchangeSpec {

    private final ExchangeFacade exchangeFacade;

    @Override
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RsData<ExchangeResponse> request(@LoginUserId Long userId,
            @RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody ExchangeRequest request) {
        return RsData.created("환전을 신청했습니다.",
                ExchangeResponse.from(exchangeFacade.request(userId, idempotencyKey, request.toCommand())));
    }

    @Override
    @GetMapping
    public RsData<Page<ExchangeResponse>> getExchanges(@LoginUserId Long userId, Pageable pageable) {
        return RsData.ok("환전 신청 목록을 조회했습니다.",
                exchangeFacade.list(userId, pageable).map(ExchangeResponse::from));
    }

    @Override
    @GetMapping("/{exchangeId}")
    public RsData<ExchangeResponse> getExchange(@LoginUserId Long userId,
            @PathVariable("exchangeId") Long exchangeId) {
        return RsData.ok("환전 신청을 조회했습니다.", ExchangeResponse.from(exchangeFacade.get(userId, exchangeId)));
    }

    @Override
    @PostMapping("/{exchangeId}/cancel")
    public RsData<ExchangeResponse> cancel(@LoginUserId Long userId, @PathVariable("exchangeId") Long exchangeId) {
        return RsData.ok("환전 신청을 취소했습니다.", ExchangeResponse.from(exchangeFacade.cancel(userId, exchangeId)));
    }
}
