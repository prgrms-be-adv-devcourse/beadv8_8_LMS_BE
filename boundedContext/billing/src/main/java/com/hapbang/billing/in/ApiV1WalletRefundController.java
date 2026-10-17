package com.hapbang.billing.in;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.billing.app.RefundFacade;
import com.hapbang.billing.app.RefundResult;
import com.hapbang.global.auth.LoginUserId;
import com.hapbang.global.response.RsData;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// 로그인한 회원 자신의 충전 HP 환불 API다 (#260). Swagger 명세는 ApiV1WalletRefundSpec에 둔다. 역할 검사는 RefundFacade가 한다.
@RestController
@RequestMapping("/api/v1/wallets/refunds")
@RequiredArgsConstructor
public class ApiV1WalletRefundController implements ApiV1WalletRefundSpec {

    private final RefundFacade refundFacade;

    // 처리가 끝났으면(COMPLETED·PARTIALLY_COMPLETED·FAILED·CANCELED) 200, 결과 확인 중·운영자 확인이면 202로
    // 같은 상태를 본문과 응답에 함께 쓴다.
    @Override
    @PostMapping
    public ResponseEntity<RsData<RefundResponse>> request(@LoginUserId Long userId,
            @RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody RefundRequest request) {
        RefundResult result = refundFacade.request(userId, idempotencyKey, request.amount());
        RefundResponse body = RefundResponse.from(result);
        if (!body.closed()) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(RsData.of(HttpStatus.ACCEPTED,
                    "환불 결과를 확인하고 있습니다.", body));
        }
        return ResponseEntity.ok(RsData.ok("환불 요청을 처리했습니다.", body));
    }

    @Override
    @GetMapping("/{refundRequestId}")
    public RsData<RefundResponse> getRefund(@LoginUserId Long userId,
            @PathVariable("refundRequestId") Long refundRequestId) {
        return RsData.ok("환불 요청을 조회했습니다.", RefundResponse.from(refundFacade.getRefund(userId, refundRequestId)));
    }

    @Override
    @PostMapping("/{refundRequestId}/cancel")
    public RsData<RefundResponse> cancel(@LoginUserId Long userId,
            @PathVariable("refundRequestId") Long refundRequestId) {
        return RsData.ok("환불 요청을 취소했습니다.", RefundResponse.from(refundFacade.cancel(userId, refundRequestId)));
    }
}
