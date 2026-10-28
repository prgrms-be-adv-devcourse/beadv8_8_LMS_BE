package com.hapbang.billing.in;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.billing.app.ChargeFacade;
import com.hapbang.billing.app.ChargeResult;
import com.hapbang.billing.domain.PointChargeStatus;
import com.hapbang.global.auth.LoginUserId;
import com.hapbang.global.response.RsData;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// 로그인한 회원 자신의 HP 충전 API다 (#224). Swagger 명세는 ApiV1WalletChargeSpec에 둔다. 역할 검사는 ChargeFacade가 한다.
@RestController
@RequestMapping("/api/v1/wallets/charges")
@RequiredArgsConstructor
public class ApiV1WalletChargeController implements ApiV1WalletChargeSpec {

    private final ChargeFacade chargeFacade;

    @Override
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RsData<ChargeResponse> createOrder(@LoginUserId Long userId,
            @Valid @RequestBody ChargeOrderRequest request) {
        return RsData.created("충전 주문을 만들었습니다.",
                ChargeResponse.from(chargeFacade.createOrder(userId, request.amount())));
    }

    // 충전되면 200, 승인 결과를 아직 확정하지 못했으면 202로 같은 상태를 본문과 응답에 함께 쓴다.
    @Override
    @PostMapping("/confirm")
    public ResponseEntity<RsData<ChargeResponse>> confirm(@LoginUserId Long userId,
            @Valid @RequestBody ChargeConfirmRequest request) {
        ChargeResult result = chargeFacade.confirm(userId, request.paymentKey(), request.orderId(), request.amount());
        if (result.status() == PointChargeStatus.PROCESSING) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(RsData.of(HttpStatus.ACCEPTED,
                    "결제 승인 결과를 확인하고 있습니다.", ChargeResponse.from(result)));
        }
        return ResponseEntity.ok(RsData.ok("HP를 충전했습니다.", ChargeResponse.from(result)));
    }

    @Override
    @GetMapping("/{orderId}")
    public RsData<ChargeResponse> getCharge(@LoginUserId Long userId, @PathVariable("orderId") String orderId) {
        return RsData.ok("충전 주문을 조회했습니다.", ChargeResponse.from(chargeFacade.getCharge(userId, orderId)));
    }
}
