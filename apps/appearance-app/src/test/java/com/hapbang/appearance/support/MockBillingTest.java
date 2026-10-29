package com.hapbang.appearance.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.RestClient;

import com.hapbang.appearance.support.MockBilling.Endpoint;
import com.hapbang.appearance.support.MockBilling.ReceivedRequest;
import com.hapbang.shared.billing.domain.BillingFailureCode;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.SettlementSourceType;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.HoldResultDto;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.dto.PayoutResultDto;
import com.hapbang.shared.billing.dto.PenaltyCommand;
import com.hapbang.shared.billing.dto.PenaltyResultDto;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.shared.billing.dto.ReleaseResultDto;
import com.hapbang.shared.billing.out.BillingApiClient;
import com.hapbang.shared.billing.out.BillingCallException;
import com.hapbang.shared.billing.out.BillingCallFailureKind;
import com.hapbang.shared.billing.out.BillingCallResult;
import com.hapbang.shared.billing.out.BillingClientProperties;
import com.hapbang.testsupport.IntegrationTest;

/**
 * Mock Billing 서버 (#391). 출연 테스트 컨텍스트에 Mock 주소를 넣으면 shared {@link BillingApiClient} 빈이 생기고,
 * 그 Client로 시나리오별 응답이 계약(`docs/api/internal-billing.md` 2장)대로 분류되는지, 받은 요청을 꺼낼 수 있는지 확인한다.
 * 성공 응답 파일이 계약·{@code *ResultDto}와 어긋나면 여기서 실패한다.
 */
class MockBillingTest extends MockBillingIntegrationTest {

    private static final long HOST = 1L;
    private static final long GUEST = 2L;

    @Autowired
    BillingApiClient billingApiClient;

    private static HoldCommand hold() {
        return new HoldCommand(HOST, BillingReferenceType.APPEARANCE_REQUEST, 12L, 30_000L);
    }

    private static ReleaseCommand release() {
        return ReleaseCommand.partial(HOST, BillingReferenceType.APPEARANCE_REQUEST, 12L,
                "APPEARANCE_REQUEST-12-RELEASE-1", 30_000L);
    }

    private static PayoutCommand payout() {
        return PayoutCommand.partial(HOST, BillingReferenceType.APPEARANCE_RECRUIT, 5L,
                "APPEARANCE_RECRUIT-5-PAYOUT-APPEARANCE-30", GUEST, SettlementSourceType.RECRUIT_APPEARANCE, 30L,
                20_000L);
    }

    private static PenaltyCommand penalty() {
        return new PenaltyCommand(HOST, BillingReferenceType.APPEARANCE_REQUEST, 12L,
                "APPEARANCE_REQUEST-12-PENALTY-APPEARANCE-30", GUEST, SettlementSourceType.APPEARANCE_REQUEST, 30L,
                21_000L, 9_000L);
    }

    private static <T> T succeeded(BillingCallResult<T> result) {
        assertThat(result).isInstanceOf(BillingCallResult.Succeeded.class);
        return ((BillingCallResult.Succeeded<T>) result).value();
    }

    private static BillingCallResult.Failed<?> failed(BillingCallResult<?> result) {
        assertThat(result).isInstanceOf(BillingCallResult.Failed.class);
        return (BillingCallResult.Failed<?>) result;
    }

    @Nested
    @DisplayName("성공")
    class Success {

        @Test
        @DisplayName("4개 경로 모두 성공 응답을 Client가 읽는다 (요청 값을 돌려줌, replayed = false)")
        void allEndpoints() {
            HoldResultDto held = succeeded(billingApiClient.hold(hold()));
            ReleaseResultDto released = succeeded(billingApiClient.release(release()));
            PayoutResultDto paid = succeeded(billingApiClient.payout(payout()));
            PenaltyResultDto settled = succeeded(billingApiClient.settlePenalty(penalty()));

            assertThat(held).isEqualTo(new HoldResultDto(BillingReferenceType.APPEARANCE_REQUEST, 12L, 30_000L, false));
            assertThat(released).isEqualTo(new ReleaseResultDto(BillingReferenceType.APPEARANCE_REQUEST, 12L,
                    "APPEARANCE_REQUEST-12-RELEASE-1", 30_000L, 0L, false));
            assertThat(paid).isEqualTo(new PayoutResultDto(BillingReferenceType.APPEARANCE_RECRUIT, 5L,
                    "APPEARANCE_RECRUIT-5-PAYOUT-APPEARANCE-30", GUEST, SettlementSourceType.RECRUIT_APPEARANCE, 30L,
                    20_000L, 0L, false));
            assertThat(settled).isEqualTo(new PenaltyResultDto(BillingReferenceType.APPEARANCE_REQUEST, 12L,
                    "APPEARANCE_REQUEST-12-PENALTY-APPEARANCE-30", GUEST, SettlementSourceType.APPEARANCE_REQUEST,
                    30L, 21_000L, 9_000L, 0L, false));
        }

        @Test
        @DisplayName("받은 요청: 경로·내부 API 키·JSON 형식·요청 ID·금액을 꺼낼 수 있다")
        void receivedRequests() {
            billingApiClient.hold(hold());
            billingApiClient.release(release());
            billingApiClient.settlePenalty(penalty());

            ReceivedRequest heldRequest = MockBilling.requests(Endpoint.HOLD).getFirst();
            assertThat(heldRequest.path()).isEqualTo("/api/v1/internal/billing/holds");
            assertThat(heldRequest.apiKey()).isEqualTo(IntegrationTest.INTERNAL_API_KEY);
            assertThat(heldRequest.contentType()).startsWith("application/json");
            assertThat(heldRequest.requestId(Endpoint.HOLD)).isNull();
            assertThat(heldRequest.text("referenceType")).isEqualTo("APPEARANCE_REQUEST");
            assertThat(heldRequest.number("amountHp")).isEqualTo(30_000L);

            assertThat(MockBilling.requests(Endpoint.RELEASE)).singleElement()
                    .satisfies(r -> assertThat(r.requestId(Endpoint.RELEASE)).isEqualTo("APPEARANCE_REQUEST-12-RELEASE-1"));
            ReceivedRequest settleRequest = MockBilling.requests(Endpoint.PENALTY).getFirst();
            assertThat(settleRequest.requestId(Endpoint.PENALTY)).isEqualTo("APPEARANCE_REQUEST-12-PENALTY-APPEARANCE-30");
            assertThat(settleRequest.number("releaseAmountHp")).isEqualTo(21_000L);
            assertThat(settleRequest.number("payoutAmountHp")).isEqualTo(9_000L);
            assertThat(MockBilling.requests(Endpoint.PAYOUT)).isEmpty();
        }

        @Test
        @DisplayName("전액 반환(금액 null): 요청에 금액이 없고, 남은 보류액을 알 수 없어 반환액 0으로 돌려준다")
        void fullRelease() {
            ReleaseResultDto released = succeeded(billingApiClient.release(ReleaseCommand.full(HOST,
                    BillingReferenceType.APPEARANCE_REQUEST, 12L, "APPEARANCE_REQUEST-12-RELEASE-1")));

            assertThat(released.releasedHp()).isZero();
            assertThat(MockBilling.requests(Endpoint.RELEASE).getFirst().number("amountHp")).isNull();
        }

        @Test
        @DisplayName("같은 요청 ID 재전송: 첫 요청 replayed = false, 다시 보내면 처음 결과 replayed = true. 받은 요청은 같은 ID로 2건")
        void replay() {
            MockBilling.replayAfterFirst(Endpoint.RELEASE, "APPEARANCE_REQUEST-12-RELEASE-1");

            ReleaseResultDto first = succeeded(billingApiClient.release(release()));
            ReleaseResultDto again = succeeded(billingApiClient.release(release()));

            assertThat(first.replayed()).isFalse();
            assertThat(again.replayed()).isTrue();
            assertThat(again.releasedHp()).isEqualTo(first.releasedHp());
            assertThat(MockBilling.requests(Endpoint.RELEASE)).extracting(r -> r.requestId(Endpoint.RELEASE))
                    .containsExactly("APPEARANCE_REQUEST-12-RELEASE-1", "APPEARANCE_REQUEST-12-RELEASE-1");
        }

        @Test
        @DisplayName("재전송은 요청 ID 단위: 다른 요청 ID는 각자 처음 처리(replayed = false), 같은 ID를 다시 보낼 때만 true")
        void replayPerRequestId() {
            MockBilling.replayAfterFirst(Endpoint.RELEASE, "APPEARANCE_REQUEST-12-RELEASE-1");
            ReleaseCommand other = ReleaseCommand.partial(HOST, BillingReferenceType.APPEARANCE_RECRUIT, 5L,
                    "APPEARANCE_RECRUIT-5-RELEASE-1", 10_000L);

            ReleaseResultDto first = succeeded(billingApiClient.release(release()));
            ReleaseResultDto otherFirst = succeeded(billingApiClient.release(other));
            ReleaseResultDto otherAgain = succeeded(billingApiClient.release(other));
            ReleaseResultDto again = succeeded(billingApiClient.release(release()));

            assertThat(first.replayed()).isFalse();
            assertThat(otherFirst.replayed()).isFalse();
            assertThat(otherAgain.replayed()).isFalse(); // 재전송 시나리오를 걸지 않은 ID는 기본(처음 처리) 응답
            assertThat(again.replayed()).isTrue();
        }

        @Test
        @DisplayName("보류 재전송은 참조 대상 단위: 같은 참조 대상이면 replayed = true, 다른 참조 대상은 처음 처리")
        void holdReplayPerReference() {
            MockBilling.replayHoldAfterFirst(BillingReferenceType.APPEARANCE_REQUEST, 12L);
            HoldCommand otherReference = new HoldCommand(HOST, BillingReferenceType.APPEARANCE_REQUEST, 13L, 30_000L);

            assertThat(succeeded(billingApiClient.hold(hold())).replayed()).isFalse();
            assertThat(succeeded(billingApiClient.hold(otherReference)).replayed()).isFalse();
            assertThat(succeeded(billingApiClient.hold(hold())).replayed()).isTrue();
        }
    }

    @Nested
    @DisplayName("거절 (4xx ProblemDetail → Failed)")
    class Rejection {

        @Test
        @DisplayName("409 INSUFFICIENT_BALANCE: 코드·사용자에게 보여 줄 detail·HTTP 상태")
        void insufficientBalance() {
            MockBilling.fail(Endpoint.HOLD, 409, "INSUFFICIENT_BALANCE", "HP 잔액이 부족합니다.");

            BillingCallResult.Failed<?> failed = failed(billingApiClient.hold(hold()));

            assertThat(failed.code()).isEqualTo(BillingFailureCode.INSUFFICIENT_BALANCE);
            assertThat(failed.detail()).isEqualTo("HP 잔액이 부족합니다.");
            assertThat(failed.httpStatus()).isEqualTo(409);
            assertThat(failed.rawCode()).isEqualTo("INSUFFICIENT_BALANCE");
        }

        @Test
        @DisplayName("409 IDEMPOTENCY_KEY_CONFLICT (같은 요청 ID, 다른 내용)")
        void idempotencyKeyConflict() {
            MockBilling.fail(Endpoint.PAYOUT, 409, "IDEMPOTENCY_KEY_CONFLICT", "같은 요청 ID로 다른 요청을 보냈습니다.");

            assertThat(failed(billingApiClient.payout(payout())).code())
                    .isEqualTo(BillingFailureCode.IDEMPOTENCY_KEY_CONFLICT);
        }

        @Test
        @DisplayName("처음 보는 코드·코드 없는 4xx는 UNMAPPED, 원래 문자열은 rawCode")
        void unmapped() {
            MockBilling.fail(Endpoint.RELEASE, 404, "SOMETHING_NEW", "새 오류");
            BillingCallResult.Failed<?> unknownCode = failed(billingApiClient.release(release()));
            MockBilling.fail(Endpoint.RELEASE, 400, null, null);
            BillingCallResult.Failed<?> noCode = failed(billingApiClient.release(release()));

            assertThat(unknownCode.code()).isEqualTo(BillingFailureCode.UNMAPPED);
            assertThat(unknownCode.rawCode()).isEqualTo("SOMETHING_NEW");
            assertThat(noCode.code()).isEqualTo(BillingFailureCode.UNMAPPED);
            assertThat(noCode.httpStatus()).isEqualTo(400);
        }
    }

    @Nested
    @DisplayName("결과를 받지 못함 (BillingCallException)")
    class NoAnswer {

        @Test
        @DisplayName("429는 RETRYABLE (지갑이 처리하지 않음)")
        void tooManyRequests() {
            MockBilling.fail(Endpoint.HOLD, 429, null, null);

            assertThatThrownBy(() -> billingApiClient.hold(hold()))
                    .isInstanceOfSatisfying(BillingCallException.class, e -> {
                        assertThat(e.getKind()).isEqualTo(BillingCallFailureKind.RETRYABLE);
                        assertThat(e.getHttpStatus()).isEqualTo(429);
                    });
        }

        @Test
        @DisplayName("5xx는 코드가 있어도 UNKNOWN_RESULT (지갑이 커밋했을 수 있음), 상태와 코드를 담는다")
        void serverError() {
            MockBilling.fail(Endpoint.RELEASE, 500, "HOLD_STATE_INCONSISTENT", "보류 상태가 맞지 않습니다.");

            assertThatThrownBy(() -> billingApiClient.release(release()))
                    .isInstanceOfSatisfying(BillingCallException.class, e -> {
                        assertThat(e.getKind()).isEqualTo(BillingCallFailureKind.UNKNOWN_RESULT);
                        assertThat(e.getHttpStatus()).isEqualTo(500);
                        assertThat(e.getRawCode()).isEqualTo("HOLD_STATE_INCONSISTENT");
                    });
        }

        @Test
        @DisplayName("응답 지연이 Client 응답 대기 시간(테스트 1초)을 넘으면 UNKNOWN_RESULT, 상태 0. 지갑은 요청을 받았다")
        void timeout() {
            MockBilling.delay(Endpoint.PAYOUT, 2_000);

            assertThatThrownBy(() -> billingApiClient.payout(payout()))
                    .isInstanceOfSatisfying(BillingCallException.class, e -> {
                        assertThat(e.getKind()).isEqualTo(BillingCallFailureKind.UNKNOWN_RESULT);
                        assertThat(e.getHttpStatus()).isZero();
                    });
            assertThat(MockBilling.requests(Endpoint.PAYOUT)).hasSize(1);
        }

        @Test
        @DisplayName("응답 도중 연결이 끊기면 UNKNOWN_RESULT, 상태 0")
        void connectionReset() {
            MockBilling.connectionReset(Endpoint.PENALTY);

            assertThatThrownBy(() -> billingApiClient.settlePenalty(penalty()))
                    .isInstanceOfSatisfying(BillingCallException.class, e -> {
                        assertThat(e.getKind()).isEqualTo(BillingCallFailureKind.UNKNOWN_RESULT);
                        assertThat(e.getHttpStatus()).isZero();
                    });
        }

        @Test
        @DisplayName("연결 거부(지갑이 떠 있지 않음)는 RETRYABLE, 상태 0")
        void connectionRefused() {
            BillingApiClient refused = new BillingApiClient(RestClient.builder(),
                    new BillingClientProperties(MockBilling.refusedBaseUrl(), null, null),
                    IntegrationTest.INTERNAL_API_KEY);

            assertThatThrownBy(() -> refused.hold(hold()))
                    .isInstanceOfSatisfying(BillingCallException.class, e -> {
                        assertThat(e.getKind()).isEqualTo(BillingCallFailureKind.RETRYABLE);
                        assertThat(e.getHttpStatus()).isZero();
                    });
        }
    }
}
