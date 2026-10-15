package com.hapbang.billing.out;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.extern.slf4j.Slf4j;

// TossPayments 코어 API 중 결제 승인·주문 ID 결제 조회를 호출한다 (#224, docs.tosspayments.com/reference).
// - 인증: Authorization: Basic base64("{시크릿 키}:")
// - 승인: POST /v1/payments/confirm {paymentKey, orderId, amount}. Idempotency-Key로 같은 주문의 재요청은 TossPayments가 처음 응답을
//   돌려준다(15일, 처리 중이면 409 IDEMPOTENT_REQUEST_PROCESSING).
// - 조회: GET /v1/payments/orders/{orderId}
// DB 트랜잭션 안에서 부르지 않는다(호출한 쪽 책임). 예외를 던지지 않고 결과를 Paid·Rejected·Unknown으로 나눠 돌려준다.
// 결제키·시크릿 키는 로그에 남기지 않는다.
@Slf4j
@Component
@EnableConfigurationProperties(TossPaymentsProperties.class)
public class TossPaymentsClient {

    static final String CONFIRM_PATH = "/v1/payments/confirm";
    static final String ORDER_LOOKUP_PATH = "/v1/payments/orders/{orderId}";
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final String IDEMPOTENCY_KEY_PREFIX = "hapbang-charge-confirm-";
    // 4xx이지만 승인 여부를 알 수 없는 코드: 이미 처리된 결제, 같은 멱등키 요청이 아직 처리 중
    static final Set<String> UNRESOLVED_CODES = Set.of("ALREADY_PROCESSED_PAYMENT", "IDEMPOTENT_REQUEST_PROCESSING");
    // PG가 결제를 판단하지 않은 4xx 상태: 인증 키 오류(우리 설정 문제), 처리 중 충돌, 요청 한도 초과. 코드와 관계없이 승인 실패로 보지 않는다
    static final Set<Integer> UNRESOLVED_STATUSES = Set.of(401, 409, 429);
    static final int NOT_FOUND = 404;

    private final RestClient restClient;
    private final boolean configured;

    public TossPaymentsClient(RestClient.Builder restClientBuilder, TossPaymentsProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        RestClient.Builder builder = restClientBuilder.clone()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory);
        this.configured = properties.isConfigured();
        if (configured) {
            String credentials = Base64.getEncoder()
                    .encodeToString((properties.secretKey() + ":").getBytes(StandardCharsets.UTF_8));
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + credentials);
        } else {
            log.warn("TossPayments 시크릿 키가 설정되지 않아 HP 충전을 사용할 수 없습니다 (hapbang.billing.toss.secret-key)");
        }
        this.restClient = builder.build();
    }

    // 시크릿 키가 설정되어 호출할 수 있는지 확인한다.
    public boolean isConfigured() {
        return configured;
    }

    // 결제를 승인한다. 같은 주문의 재요청은 같은 멱등키를 써서 TossPayments가 중복 승인하지 않게 한다.
    public TossResult confirm(String paymentKey, String orderId, long amount) {
        return call("승인", orderId, () -> restClient.post()
                .uri(CONFIRM_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header(IDEMPOTENCY_KEY_HEADER, IDEMPOTENCY_KEY_PREFIX + orderId)
                .body(Map.of("paymentKey", paymentKey, "orderId", orderId, "amount", amount))
                .retrieve()
                .body(TossPayment.class));
    }

    // 주문 ID로 결제를 조회한다. 결제가 없으면(404) NotFound다. 실제 TossPayments는 인증만 하고 승인 전인 결제도 404로 답한다
    // (2026-10-15 실제 테스트 결제로 확인). 그 밖의 4xx는 승인 여부를 확정할 수 없으므로 Unknown이다.
    public TossResult findByOrderId(String orderId) {
        TossResult result = call("조회", orderId, () -> restClient.get()
                .uri(ORDER_LOOKUP_PATH, orderId)
                .retrieve()
                .body(TossPayment.class));
        if (result instanceof TossResult.Rejected rejected && rejected.httpStatus() == NOT_FOUND) {
            return new TossResult.NotFound(rejected.code());
        }
        if (result instanceof TossResult.Rejected rejected) {
            return new TossResult.Unknown("LOOKUP_" + rejected.httpStatus() + ":" + rejected.code());
        }
        return result;
    }

    private TossResult call(String action, String orderId, PaymentCall call) {
        try {
            TossPayment payment = call.execute();
            if (payment == null) {
                log.warn("TossPayments {} 응답 본문 없음: orderId={}", action, orderId);
                return new TossResult.Unknown("EMPTY_RESPONSE");
            }
            return new TossResult.Paid(payment);
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            TossError error = errorOf(exception);
            log.warn("TossPayments {} 오류 응답: orderId={}, status={}, code={}", action, orderId, status, error.code());
            if (status >= 500 || UNRESOLVED_STATUSES.contains(status) || UNRESOLVED_CODES.contains(error.code())) {
                return new TossResult.Unknown(status + ":" + error.code());
            }
            if (status >= 400) {
                return new TossResult.Rejected(status, error.code(), error.message());
            }
            return new TossResult.Unknown(status + ":" + error.code());
        } catch (ResourceAccessException exception) {
            // 연결 실패·응답 시간 초과: 요청이 TossPayments에 닿았는지 알 수 없다
            log.warn("TossPayments {} 응답 없음(연결·시간 초과): orderId={}, cause={}", action, orderId,
                    exception.getClass().getSimpleName());
            return new TossResult.Unknown("NO_RESPONSE");
        } catch (RestClientException exception) {
            // 응답 본문을 읽다가 시간 초과·연결 끊김이 나면 여기로 온다(원인이 IOException)
            if (causedByIo(exception)) {
                log.warn("TossPayments {} 응답 중단(시간 초과·연결 끊김): orderId={}", action, orderId);
                return new TossResult.Unknown("NO_RESPONSE");
            }
            log.warn("TossPayments {} 응답 해석 실패: orderId={}, cause={}", action, orderId,
                    exception.getClass().getSimpleName());
            return new TossResult.Unknown("UNREADABLE_RESPONSE");
        }
    }

    private static boolean causedByIo(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    private static TossError errorOf(RestClientResponseException exception) {
        try {
            TossError error = exception.getResponseBodyAs(TossError.class);
            if (error != null && error.code() != null) {
                return error;
            }
        } catch (RuntimeException ignored) {
            // 본문이 TossPayments 오류 형식이 아니면 코드 없이 상태로만 판단한다
        }
        return new TossError("UNKNOWN", null);
    }

    @FunctionalInterface
    private interface PaymentCall {
        TossPayment execute();
    }

    // TossPayments 오류 응답 {code, message}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TossError(String code, String message) {
    }
}
