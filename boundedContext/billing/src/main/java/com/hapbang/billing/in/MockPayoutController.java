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

import com.hapbang.billing.app.MockPayoutBank;
import com.hapbang.billing.app.MockPayoutTransferView;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.MockPayoutTransferStatus;
import com.hapbang.billing.out.ConditionalOnMockPayout;
import com.hapbang.billing.out.MockPayoutAccessKey;
import com.hapbang.global.response.RsData;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// 가상 송금 Mock 은행 API (#288). 설정(enabled=true)과 활성 프로필(하나 이상, 전부 local·test)이 모두 맞을 때만 켜진다(MockPayoutActivation).
// dev·prod에서는 설정이 잘못 들어가도 빈이 없어 경로가 없다.
// 같은 애플리케이션의 가상 송금 HTTP Client(MockPayoutHttpClient)만 부른다: 세션 없이 오므로 MockPayoutSecurityConfig가 이 경로만
// 열고, 앱이 뜰 때 만든 접근 키(X-Mock-Payout-Key)로 외부 호출을 403으로 막는다. 실제 은행·돈 이동은 없다.
// - POST: 지급 요청 ID(UUID) = Idempotency-Key. 결과가 정해졌으면 200(SUCCEEDED·FAILED), 같은 ID의 앞선 요청이 처리 중이면 202 PROCESSING.
//   같은 ID·다른 내용 409, 키·본문 ID 불일치 400, SERVER_ERROR 시나리오 500(처리 안 함)
// - GET /{payoutRequestId}: 저장된 결과(SUCCEEDED·FAILED·PROCESSING), 없으면 404 MOCK_PAYOUT_NOT_FOUND
@Hidden
@RestController
@RequestMapping(MockPayoutController.PATH)
@ConditionalOnMockPayout
@RequiredArgsConstructor
public class MockPayoutController {

    public static final String PATH = "/api/v1/wallets/mock-payouts";

    private final MockPayoutBank mockPayoutBank;
    private final MockPayoutAccessKey accessKey;

    @PostMapping
    public ResponseEntity<RsData<MockPayoutResponse>> transfer(
            @RequestHeader(name = MockPayoutAccessKey.HEADER, required = false) String key,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody MockPayoutRequest request) {
        requireAccess(key);
        if (!idempotencyKey.equals(request.payoutRequestId())) {
            throw new BillingException(BillingErrorCode.MOCK_PAYOUT_INVALID_REQUEST);
        }
        MockPayoutTransferView view = mockPayoutBank.transfer(request.toCommand());
        if (view.status() == MockPayoutTransferStatus.PROCESSING) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(RsData.of(HttpStatus.ACCEPTED,
                    "가상 송금을 처리하고 있습니다.", MockPayoutResponse.from(view)));
        }
        return ResponseEntity.ok(RsData.ok("가상 송금을 처리했습니다.", MockPayoutResponse.from(view)));
    }

    @GetMapping("/{payoutRequestId}")
    public RsData<MockPayoutResponse> find(@RequestHeader(name = MockPayoutAccessKey.HEADER, required = false) String key,
            @PathVariable("payoutRequestId") String payoutRequestId) {
        requireAccess(key);
        return RsData.ok("가상 송금 결과를 조회했습니다.", MockPayoutResponse.from(mockPayoutBank.find(payoutRequestId)));
    }

    private void requireAccess(String key) {
        if (!accessKey.matches(key)) {
            throw new BillingException(BillingErrorCode.MOCK_PAYOUT_FORBIDDEN);
        }
    }
}
