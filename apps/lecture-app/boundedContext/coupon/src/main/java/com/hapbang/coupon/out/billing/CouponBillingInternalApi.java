package com.hapbang.coupon.out.billing;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import com.hapbang.shared.billing.dto.PromotionBudgetReleaseCommand;
import com.hapbang.shared.billing.dto.PromotionBudgetReleaseResultDto;
import com.hapbang.shared.billing.dto.PromotionBudgetReserveCommand;
import com.hapbang.shared.billing.dto.PromotionBudgetReserveResultDto;
import com.hapbang.shared.billing.dto.PromotionBudgetWorkDto;

/** 쿠폰 예산 예약·해제·작업 조회의 내부 HTTP 계약. 요청과 결과 데이터는 billing의 shared 계약을 그대로 쓴다. */
@HttpExchange(CouponBillingInternalApi.PATH)
public interface CouponBillingInternalApi {

    String PATH = "/api/v1/internal/billing/promotion-budgets";

    @PostExchange("/reservations")
    ResponseEntity<Envelope<PromotionBudgetReserveResultDto>> reserve(@RequestBody PromotionBudgetReserveCommand command);

    @PostExchange("/releases")
    ResponseEntity<Envelope<PromotionBudgetReleaseResultDto>> release(@RequestBody PromotionBudgetReleaseCommand command);

    @GetExchange("/works/{workId}")
    ResponseEntity<Envelope<PromotionBudgetWorkDto>> status(@PathVariable("workId") String workId);

    /** 쿠폰은 billing의 global 응답 클래스에 의존하지 않고 성공 봉투 모양만 읽는다. */
    record Envelope<T>(Integer statusCode, String message, T data) {
    }
}
