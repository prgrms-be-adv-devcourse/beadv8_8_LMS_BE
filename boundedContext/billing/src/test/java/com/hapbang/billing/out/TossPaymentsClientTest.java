package com.hapbang.billing.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.hapbang.billing.fixture.FakeTossPaymentsServer;
import com.hapbang.billing.fixture.FakeTossPaymentsServer.Fault;
import com.hapbang.billing.fixture.FakeTossPaymentsServer.Request;

// TossPayments 호출과 응답 분류(Paid·Rejected·Unknown)를 가짜 HTTP 서버로 확인한다 (#224). 실제 TossPayments는 부르지 않는다.
class TossPaymentsClientTest {

    private static final String SECRET_KEY = "test_sk_fake_for_unit_test";
    private static final String ORDER_ID = "HPCHG-unit-order-1";
    private static final String PAYMENT_KEY = "tgen_unit_payment_key";
    private static final FakeTossPaymentsServer SERVER = new FakeTossPaymentsServer();

    private final TossPaymentsClient client = new TossPaymentsClient(RestClient.builder(),
            new TossPaymentsProperties(SERVER.baseUrl(), SECRET_KEY, Duration.ofSeconds(1), Duration.ofMillis(500)));

    @BeforeEach
    void setUp() {
        SERVER.reset();
        SERVER.timeoutMillis(1_000L);
        SERVER.authorize(ORDER_ID, PAYMENT_KEY, 10_000L);
    }

    @AfterAll
    static void tearDown() {
        SERVER.close();
    }

    @Test
    @DisplayName("승인은 Basic(시크릿 키 + ':') 인증·주문별 멱등키·paymentKey·orderId·amount로 요청하고 Payment를 Paid로 돌려준다")
    void confirm() {
        TossResult result = client.confirm(PAYMENT_KEY, ORDER_ID, 10_000L);

        assertThat(result).isInstanceOfSatisfying(TossResult.Paid.class, paid -> {
            assertThat(paid.payment().isDone()).isTrue();
            assertThat(paid.payment().orderId()).isEqualTo(ORDER_ID);
            assertThat(paid.payment().paymentKey()).isEqualTo(PAYMENT_KEY);
            assertThat(paid.payment().totalAmount()).isEqualTo(10_000L);
        });
        Request request = SERVER.requests().getFirst();
        assertThat(request.authorization()).isEqualTo("Basic "
                + Base64.getEncoder().encodeToString((SECRET_KEY + ":").getBytes(StandardCharsets.UTF_8)));
        assertThat(request.idempotencyKey()).isEqualTo("hapbang-charge-confirm-" + ORDER_ID);
        assertThat(request.body()).contains("\"paymentKey\":\"" + PAYMENT_KEY + "\"", "\"orderId\":\"" + ORDER_ID + "\"",
                "\"amount\":10000");
    }

    @Test
    @DisplayName("카드 거절(403)·잘못된 요청(400)은 승인 안 됨이 확정된 Rejected(코드·메시지)다")
    void rejected() {
        SERVER.nextConfirm(ORDER_ID, Fault.REJECT_CARD);
        assertThat(client.confirm(PAYMENT_KEY, ORDER_ID, 10_000L)).isEqualTo(new TossResult.Rejected(403,
                "REJECT_CARD_PAYMENT", "한도초과 혹은 잔액부족으로 결제에 실패했습니다."));
    }

    @Test
    @DisplayName("5xx·이미 처리됨(ALREADY_PROCESSED_PAYMENT)·응답 시간 초과는 승인 여부를 모르는 Unknown이다")
    void unknown() {
        SERVER.nextConfirm(ORDER_ID, Fault.SERVER_ERROR);
        assertThat(client.confirm(PAYMENT_KEY, ORDER_ID, 10_000L))
                .isEqualTo(new TossResult.Unknown("500:FAILED_INTERNAL_SYSTEM_PROCESSING"));

        SERVER.reset();
        SERVER.authorize(ORDER_ID, PAYMENT_KEY, 10_000L);
        SERVER.nextConfirm(ORDER_ID, Fault.ALREADY_PROCESSED);
        assertThat(client.confirm(PAYMENT_KEY, ORDER_ID, 10_000L))
                .isEqualTo(new TossResult.Unknown("400:ALREADY_PROCESSED_PAYMENT"));

        SERVER.reset();
        SERVER.authorize(ORDER_ID, PAYMENT_KEY, 10_000L);
        SERVER.nextConfirm(ORDER_ID, Fault.APPROVE_THEN_TIMEOUT);
        assertThat(client.confirm(PAYMENT_KEY, ORDER_ID, 10_000L)).isEqualTo(new TossResult.Unknown("NO_RESPONSE"));
        // 응답은 유실됐지만 PG에서는 승인되었다
        assertThat(SERVER.statusOf(ORDER_ID)).isEqualTo("DONE");
    }

    @Test
    @DisplayName("주문 ID 조회는 승인된 결제를 Paid로, 결제 없음·승인 전(404)은 NotFound로, 5xx는 Unknown으로 돌려준다")
    void findByOrderId() {
        // 실제 TossPayments처럼 인증만 하고 승인 전인 결제는 404다
        assertThat(client.findByOrderId(ORDER_ID)).isEqualTo(new TossResult.NotFound("NOT_FOUND_PAYMENT"));
        assertThat(client.findByOrderId("HPCHG-missing")).isEqualTo(new TossResult.NotFound("NOT_FOUND_PAYMENT"));
        client.confirm(PAYMENT_KEY, ORDER_ID, 10_000L);
        assertThat(client.findByOrderId(ORDER_ID)).isInstanceOfSatisfying(TossResult.Paid.class,
                paid -> assertThat(paid.payment().status()).isEqualTo("DONE"));
        SERVER.nextLookup(ORDER_ID, Fault.SERVER_ERROR);
        assertThat(client.findByOrderId(ORDER_ID))
                .isEqualTo(new TossResult.Unknown("500:FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING"));
    }

    @Test
    @DisplayName("결제를 판단하지 않은 4xx(409 처리 중·429 요청 한도·401 키 오류)는 승인 실패가 아니라 Unknown이다")
    void unresolved4xx() {
        SERVER.nextConfirm(ORDER_ID, Fault.CONFLICT_PROCESSING);
        assertThat(client.confirm(PAYMENT_KEY, ORDER_ID, 10_000L))
                .isEqualTo(new TossResult.Unknown("409:IDEMPOTENT_REQUEST_PROCESSING"));
        SERVER.nextConfirm(ORDER_ID, Fault.RATE_LIMITED);
        assertThat(client.confirm(PAYMENT_KEY, ORDER_ID, 10_000L)).isEqualTo(new TossResult.Unknown("429:TOO_MANY_REQUESTS"));
        SERVER.nextConfirm(ORDER_ID, Fault.AUTH_ERROR);
        // 401은 HttpURLConnection이 오류 본문을 넘기지 않아 코드 없이 상태로만 판단한다
        assertThat(client.confirm(PAYMENT_KEY, ORDER_ID, 10_000L)).isInstanceOfSatisfying(TossResult.Unknown.class,
                unknown -> assertThat(unknown.reason()).startsWith("401:"));
        // 결제를 판단하지 않았으므로 PG의 결제는 그대로 승인 전이다
        assertThat(SERVER.statusOf(ORDER_ID)).isEqualTo("READY");
    }

    @Test
    @DisplayName("시크릿 키가 없으면 꺼진 상태다. 설정 문자열에도 시크릿 키를 드러내지 않는다")
    void notConfigured() {
        TossPaymentsProperties none = new TossPaymentsProperties(null, " ", null, null);
        TossPaymentsClient disabled = new TossPaymentsClient(RestClient.builder(), none);

        assertThat(disabled.isConfigured()).isFalse();
        assertThat(none.baseUrl()).isEqualTo("https://api.tosspayments.com");
        assertThat(new TossPaymentsProperties(null, SECRET_KEY, null, null).toString()).doesNotContain(SECRET_KEY);
    }
}
