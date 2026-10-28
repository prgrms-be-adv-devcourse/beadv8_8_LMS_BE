package com.hapbang.billing.out;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.extern.slf4j.Slf4j;

// 가상 송금 HTTP Client (#288). 같은 애플리케이션의 Mock 은행 API(MockPayoutController)에 실제 HTTP 요청을 보낸다(메서드 직접 호출 아님).
// - 금융 트랜잭션과 분리: DB 트랜잭션 안에서 부르면 HTTP를 보내기 전에 IllegalStateException이다. 같은 애플리케이션을 동기 호출하므로
//   트랜잭션(지갑·환전·보류 락) 안에서 기다리면 Mock 쪽 처리·다른 요청이 그 락을 기다려 교착·지연될 수 있다.
// - 주소: baseUrl 설정이 없으면 http://localhost:{실제 서버 포트}(local.server.port, 없으면 server.port, 기본 8080)를 호출 때마다 정한다.
// - 시간 제한: 연결 connectTimeout, 응답 readTimeout(MockPayoutProperties). 재시도하지 않는다(요청 1번 = HTTP 1번).
// - 분류: 200 SUCCEEDED/FAILED → Succeeded/Failed, 202·PROCESSING → Processing, 404(조회) → NotFound, 409 → Conflict,
//   그 밖의 4xx → Rejected, 5xx·시간 초과·연결 실패·응답 해석 실패 → Unknown(결과 불명확, 같은 ID로 조회해 복구)
// - Mock이 꺼진 환경(운영·dev, 또는 설정이 잘못 켜졌어도 dev·prod 프로필)에서는 HTTP 없이 IllegalStateException이다.
//   계좌번호·접근 키는 로그에 남기지 않는다.
@Slf4j
@Component
@EnableConfigurationProperties(MockPayoutProperties.class)
public class MockPayoutHttpClient implements PayoutTransferClient {

    static final String PATH = "/api/v1/wallets/mock-payouts";
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final RestClient restClient;
    private final MockPayoutProperties properties;
    private final boolean available;
    private final MockPayoutAccessKey accessKey;
    private final Environment environment;

    public MockPayoutHttpClient(RestClient.Builder restClientBuilder, MockPayoutProperties properties,
            MockPayoutAccessKey accessKey, Environment environment) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = restClientBuilder.clone().requestFactory(requestFactory).build();
        this.properties = properties;
        this.accessKey = accessKey;
        this.environment = environment;
        this.available = MockPayoutActivation.isActive(environment);
        if (MockPayoutActivation.requested(environment) && !available) {
            log.warn("가상 송금(Mock)이 설정으로 켜져 있지만 활성 프로필이 허용되지 않아 끈다(활성 프로필이 전부 local·test일 때만, 프로필 미지정 불가)");
        }
    }

    // Mock 은행을 부를 수 있는지(MockPayoutActivation: 설정 + 활성 프로필이 전부 local·test)
    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public PayoutTransferResult send(PayoutTransferRequest request) {
        requireCallable();
        String id = request.payoutRequestId();
        try {
            ResponseEntity<Envelope> response = restClient.post()
                    .uri(baseUrl() + PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(IDEMPOTENCY_KEY_HEADER, id)
                    .header(MockPayoutAccessKey.HEADER, accessKey.value())
                    .body(Map.of("payoutRequestId", id, "exchangeId", request.exchangeId(),
                            "amountKrw", request.amountKrw(), "bankCode", request.bankCode(),
                            "accountNumber", request.accountNumber(), "accountHolderName", request.accountHolderName()))
                    .retrieve()
                    .toEntity(new ParameterizedTypeReference<>() {
                    });
            return success("송금", id, response);
        } catch (RestClientResponseException exception) {
            return failure("송금", id, exception);
        } catch (RestClientException exception) {
            return unreachable("송금", id, exception);
        }
    }

    @Override
    public PayoutTransferResult find(String payoutRequestId) {
        requireCallable();
        try {
            ResponseEntity<Envelope> response = restClient.get()
                    .uri(baseUrl() + PATH + "/{id}", payoutRequestId)
                    .header(MockPayoutAccessKey.HEADER, accessKey.value())
                    .retrieve()
                    .toEntity(new ParameterizedTypeReference<>() {
                    });
            return success("조회", payoutRequestId, response);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == HttpStatus.NOT_FOUND.value()
                    && "MOCK_PAYOUT_NOT_FOUND".equals(codeOf(exception))) {
                return new PayoutTransferResult.NotFound(payoutRequestId);
            }
            return failure("조회", payoutRequestId, exception);
        } catch (RestClientException exception) {
            return unreachable("조회", payoutRequestId, exception);
        }
    }

    // 트랜잭션 안(락 보유 가능)에서는 같은 애플리케이션으로 동기 HTTP를 보내지 않는다.
    private void requireCallable() {
        if (!available) {
            throw new IllegalStateException("가상 송금(Mock)이 꺼져 있어 송금 Client를 쓸 수 없습니다.");
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("송금 HTTP 요청은 DB 트랜잭션 밖에서만 보낸다(락을 쥔 채 기다리지 않는다).");
        }
    }

    private String baseUrl() {
        if (properties.baseUrl() != null && !properties.baseUrl().isBlank()) {
            return properties.baseUrl();
        }
        String port = environment.getProperty("local.server.port", environment.getProperty("server.port", "8080"));
        return "http://localhost:" + port;
    }

    private static PayoutTransferResult success(String action, String id, ResponseEntity<Envelope> response) {
        Body body = response.getBody() == null ? null : response.getBody().data();
        if (body == null || body.status() == null || !id.equals(body.payoutRequestId())) {
            log.warn("가상 송금 {} 응답 해석 실패: payoutRequestId={}, status={}", action, id,
                    response.getStatusCode().value());
            return new PayoutTransferResult.Unknown(id, "UNREADABLE_RESPONSE");
        }
        return switch (body.status()) {
            case "SUCCEEDED" -> body.transactionId() == null
                    ? new PayoutTransferResult.Unknown(id, "SUCCEEDED_WITHOUT_TRANSACTION")
                    : new PayoutTransferResult.Succeeded(id, body.transactionId(), body.amountKrw(), body.completedAt());
            case "FAILED" -> new PayoutTransferResult.Failed(id, body.failureCode(), body.completedAt());
            case "PROCESSING" -> new PayoutTransferResult.Processing(id);
            default -> new PayoutTransferResult.Unknown(id, "UNKNOWN_STATUS:" + body.status());
        };
    }

    private static PayoutTransferResult failure(String action, String id, RestClientResponseException exception) {
        int status = exception.getStatusCode().value();
        String code = codeOf(exception);
        log.warn("가상 송금 {} 오류 응답: payoutRequestId={}, status={}, code={}", action, id, status, code);
        if (status == HttpStatus.CONFLICT.value()) {
            return new PayoutTransferResult.Conflict(id, code);
        }
        if (status >= 500) {
            return new PayoutTransferResult.Unknown(id, status + ":" + code);
        }
        return new PayoutTransferResult.Rejected(id, status, code);
    }

    // 연결 실패·시간 초과·응답 중 끊김·본문 해석 실패: 요청이 처리됐는지 알 수 없다
    private static PayoutTransferResult unreachable(String action, String id, RestClientException exception) {
        String reason = exception instanceof ResourceAccessException || causedByIo(exception)
                ? "NO_RESPONSE" : "UNREADABLE_RESPONSE";
        log.warn("가상 송금 {} 결과 불명확: payoutRequestId={}, reason={}, cause={}", action, id, reason,
                exception.getClass().getSimpleName());
        return new PayoutTransferResult.Unknown(id, reason);
    }

    private static boolean causedByIo(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    private static String codeOf(RestClientResponseException exception) {
        try {
            ErrorBody error = exception.getResponseBodyAs(ErrorBody.class);
            if (error != null && error.code() != null) {
                return error.code();
            }
        } catch (RuntimeException ignored) {
            // ProblemDetail 형식이 아니면(보안 필터 403 등) 코드 없이 상태로만 판단한다
        }
        return "UNKNOWN";
    }

    // RsData 응답 {statusCode, message, data}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Envelope(Body data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Body(String payoutRequestId, Long exchangeId, long amountKrw, String status, String transactionId,
            String failureCode, Instant completedAt) {
    }

    // ProblemDetail 오류 응답의 code
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorBody(String code) {
    }
}
