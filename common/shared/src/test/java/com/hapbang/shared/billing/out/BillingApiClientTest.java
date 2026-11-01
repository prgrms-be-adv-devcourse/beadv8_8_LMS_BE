package com.hapbang.shared.billing.out;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.hapbang.shared.billing.domain.BillingFailureCode;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.HoldVoidOutcome;
import com.hapbang.shared.billing.domain.SettlementSourceType;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.HoldResultDto;
import com.hapbang.shared.billing.dto.HoldVoidCommand;
import com.hapbang.shared.billing.dto.HoldVoidResultDto;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.dto.PayoutResultDto;
import com.hapbang.shared.billing.dto.PenaltyCommand;
import com.hapbang.shared.billing.dto.PenaltyResultDto;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.shared.billing.dto.ReleaseResultDto;
import com.hapbang.shared.internal.InternalApiHeaders;
import com.sun.net.httpserver.HttpServer;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

// 지갑 내부 API Client의 요청 모양·응답 해제·실패 분류(D64)를 가짜 HTTP 서버로 확인한다 (#272). 실제 지갑 API는 #273이 확인한다.
// Circuit Breaker·짧은 Retry·TimeLimiter(#411)도 여기서 확인한다. 재시도 대기는 테스트 시간을 줄이려고 10ms로 둔다.
class BillingApiClientTest {

    private static final String API_KEY = "unit-internal-api-key";
    private static final BillingClientProperties.Retry FAST_RETRY =
            new BillingClientProperties.Retry(null, Duration.ofMillis(10), null, null);
    private static final String HOLD_SUCCEEDED = """
            {"statusCode":200,"message":"","data":{
              "referenceType":"LECTURE_ORDER_ITEM","referenceId":31,"heldHp":10000,"replayed":false}}""";

    private HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile int status;
    private volatile String contentType;
    private volatile String body;
    private volatile long delayMillis;
    /** 차례로 쓸 응답. 비면 {@link #respond}로 정한 응답을 쓴다 */
    private final Deque<Response> queued = new ConcurrentLinkedDeque<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders().getFirst(InternalApiHeaders.API_KEY),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            sleep(delayMillis);
            Response next = queued.poll();
            Response response = next != null ? next : new Response(status, contentType, body);
            byte[] bytes = response.body() == null ? new byte[0] : response.body().getBytes(StandardCharsets.UTF_8);
            if (response.contentType() != null) {
                exchange.getResponseHeaders().add("Content-Type", response.contentType());
            }
            exchange.sendResponseHeaders(response.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        // 시간 초과를 일으킨 지연 응답이 다음 요청을 막지 않게 요청마다 스레드를 쓴다
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void 보류는_holds_경로에_내부_키와_JSON_본문으로_보내고_결과를_돌려준다() {
        respond(200, "application/json", """
                {"statusCode":200,"message":"HP를 보류했습니다.","data":{
                  "referenceType":"LECTURE_ORDER_ITEM","referenceId":31,"heldHp":10000,"replayed":false}}""");

        BillingCallResult<HoldResultDto> result = client().hold(
                new HoldCommand(7L, BillingReferenceType.LECTURE_ORDER_ITEM, 31L, 10_000L));

        assertThat(requests).containsExactly(new Request("POST", "/api/v1/internal/billing/holds", API_KEY,
                "application/json",
                "{\"userId\":7,\"referenceType\":\"LECTURE_ORDER_ITEM\",\"referenceId\":31,\"amountHp\":10000}"));
        assertThat(result).isEqualTo(new BillingCallResult.Succeeded<>(
                new HoldResultDto(BillingReferenceType.LECTURE_ORDER_ITEM, 31L, 10_000L, false)));
    }

    @Test
    void 반환은_releases_경로로_보내고_전체_반환이면_amountHp가_null이다() {
        respond(200, "application/json", """
                {"statusCode":200,"message":"HP를 반환했습니다.","data":{
                  "referenceType":"LECTURE_ORDER_ITEM","referenceId":31,"releaseRequestId":"LECTURE-CANCEL-31",
                  "releasedHp":10000,"remainingHeldHp":0,"replayed":true}}""");

        BillingCallResult<ReleaseResultDto> result = client().release(ReleaseCommand.full(7L,
                BillingReferenceType.LECTURE_ORDER_ITEM, 31L, "LECTURE-CANCEL-31"));

        assertThat(requests).containsExactly(new Request("POST", "/api/v1/internal/billing/releases", API_KEY,
                "application/json", "{\"userId\":7,\"referenceType\":\"LECTURE_ORDER_ITEM\",\"referenceId\":31,"
                        + "\"releaseRequestId\":\"LECTURE-CANCEL-31\",\"amountHp\":null}"));
        assertThat(result).isEqualTo(new BillingCallResult.Succeeded<>(new ReleaseResultDto(
                BillingReferenceType.LECTURE_ORDER_ITEM, 31L, "LECTURE-CANCEL-31", 10_000L, 0L, true)));
    }

    @Test
    void 보류_무효화는_hold_voids_경로로_보내고_결과를_돌려준다() {
        respond(200, "application/json", """
                {"statusCode":200,"message":"HP 보류를 무효화했습니다.","data":{
                  "referenceType":"LECTURE_ORDER_ITEM","referenceId":31,"outcome":"VOIDED",
                  "releasedHp":0,"replayed":false}}""");

        BillingCallResult<HoldVoidResultDto> result = client().voidHold(
                new HoldVoidCommand(7L, BillingReferenceType.LECTURE_ORDER_ITEM, 31L));

        assertThat(requests).containsExactly(new Request("POST", "/api/v1/internal/billing/hold-voids", API_KEY,
                "application/json", "{\"userId\":7,\"referenceType\":\"LECTURE_ORDER_ITEM\",\"referenceId\":31}"));
        assertThat(result).isEqualTo(new BillingCallResult.Succeeded<>(new HoldVoidResultDto(
                BillingReferenceType.LECTURE_ORDER_ITEM, 31L, HoldVoidOutcome.VOIDED, 0L, false)));
    }

    @Test
    void 지급과_위약금_정산은_각자의_경로로_보낸다() {
        respond(200, "application/json", """
                {"statusCode":200,"message":"HP를 지급했습니다.","data":{
                  "referenceType":"LECTURE_ORDER_ITEM","referenceId":31,"payoutRequestId":"LECTURE-PAYOUT-31",
                  "receiverUserId":9,"sourceType":"LECTURE_ORDER_ITEM","sourceId":31,
                  "paidHp":10000,"remainingHeldHp":0,"replayed":false}}""");

        BillingCallResult<PayoutResultDto> payout = client().payout(PayoutCommand.full(7L,
                BillingReferenceType.LECTURE_ORDER_ITEM, 31L, "LECTURE-PAYOUT-31", 9L,
                SettlementSourceType.LECTURE_ORDER_ITEM, 31L));

        assertThat(payout).isEqualTo(new BillingCallResult.Succeeded<>(new PayoutResultDto(
                BillingReferenceType.LECTURE_ORDER_ITEM, 31L, "LECTURE-PAYOUT-31", 9L,
                SettlementSourceType.LECTURE_ORDER_ITEM, 31L, 10_000L, 0L, false)));

        respond(200, "application/json", """
                {"statusCode":200,"message":"위약금을 정산했습니다.","data":{
                  "referenceType":"APPEARANCE_REQUEST","referenceId":5,"cancellationId":"CANCEL-5",
                  "receiverUserId":9,"sourceType":"APPEARANCE_REQUEST","sourceId":12,
                  "releasedHp":7000,"paidHp":3000,"remainingHeldHp":0,"replayed":false}}""");

        BillingCallResult<PenaltyResultDto> penalty = client().settlePenalty(new PenaltyCommand(7L,
                BillingReferenceType.APPEARANCE_REQUEST, 5L, "CANCEL-5", 9L,
                SettlementSourceType.APPEARANCE_REQUEST, 12L, 7_000L, 3_000L));

        assertThat(penalty).isEqualTo(new BillingCallResult.Succeeded<>(new PenaltyResultDto(
                BillingReferenceType.APPEARANCE_REQUEST, 5L, "CANCEL-5", 9L, SettlementSourceType.APPEARANCE_REQUEST,
                12L, 7_000L, 3_000L, 0L, false)));
        assertThat(requests).extracting(Request::path).containsExactly(
                "/api/v1/internal/billing/payouts", "/api/v1/internal/billing/penalty-settlements");
        assertThat(requests.getFirst().body()).isEqualTo("{\"payerUserId\":7,\"referenceType\":\"LECTURE_ORDER_ITEM\","
                + "\"referenceId\":31,\"payoutRequestId\":\"LECTURE-PAYOUT-31\",\"receiverUserId\":9,"
                + "\"sourceType\":\"LECTURE_ORDER_ITEM\",\"sourceId\":31,\"amountHp\":null}");
    }

    @Test
    void 클라이언트_오류의_code를_BillingFailureCode로_바꾸고_detail과_상태를_담는다() {
        respond(409, "application/problem+json", """
                {"status":409,"detail":"HP 잔액이 부족합니다.","code":"INSUFFICIENT_BALANCE"}""");

        BillingCallResult<HoldResultDto> result = client().hold(
                new HoldCommand(7L, BillingReferenceType.LECTURE_ORDER_ITEM, 31L, 10_000L));

        assertThat(result).isEqualTo(new BillingCallResult.Failed<>(BillingFailureCode.INSUFFICIENT_BALANCE,
                "HP 잔액이 부족합니다.", 409, "INSUFFICIENT_BALANCE"));
    }

    @Test
    void 처음_보는_code나_본문_없는_클라이언트_오류는_UNMAPPED이고_원래_코드를_보존한다() {
        HoldCommand command = new HoldCommand(7L, BillingReferenceType.LECTURE_ORDER_ITEM, 31L, 10_000L);
        respond(400, "application/problem+json", """
                {"status":400,"detail":"요청 값이 올바르지 않습니다.","code":"INVALID_REQUEST"}""");

        assertThat(client().hold(command)).isEqualTo(new BillingCallResult.Failed<>(BillingFailureCode.UNMAPPED,
                "요청 값이 올바르지 않습니다.", 400, "INVALID_REQUEST"));

        // 내부 API 키 오류는 본문이 없다
        respond(401, null, null);
        assertThat(client().hold(command))
                .isEqualTo(new BillingCallResult.Failed<>(BillingFailureCode.UNMAPPED, null, 401, null));
    }

    @Test
    void 처리하지_않았다는_408_425_429는_재시도_가능이다() {
        HoldCommand command = new HoldCommand(7L, BillingReferenceType.LECTURE_ORDER_ITEM, 31L, 10_000L);
        respond(429, "application/problem+json", """
                {"status":429,"detail":"요청이 너무 많습니다.","code":"TOO_MANY_REQUESTS"}""");
        assertRetryable(() -> client().hold(command), 429, "TOO_MANY_REQUESTS");

        respond(408, null, null);
        assertRetryable(() -> client().hold(command), 408, null);

        respond(425, null, null);
        assertRetryable(() -> client().hold(command), 425, null);
    }

    private static void assertRetryable(Runnable call, int status, String rawCode) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BillingCallException.class, e -> {
            assertThat(e.getKind()).isEqualTo(BillingCallFailureKind.RETRYABLE);
            assertThat(e.getHttpStatus()).isEqualTo(status);
            assertThat(e.getRawCode()).isEqualTo(rawCode);
        });
    }

    @Test
    void 서버_오류는_code가_있어도_결과_불명이다() {
        HoldCommand command = new HoldCommand(7L, BillingReferenceType.LECTURE_ORDER_ITEM, 31L, 10_000L);
        respond(500, "application/problem+json", """
                {"status":500,"detail":"HP 처리 중 오류가 발생했습니다.","code":"HOLD_STATE_INCONSISTENT"}""");
        assertUnknown(() -> client().hold(command), 500, "HOLD_STATE_INCONSISTENT");

        respond(503, null, null);
        assertUnknown(() -> client().hold(command), 503, null);
    }

    @Test
    void 성공_응답을_읽지_못하거나_data가_없으면_결과_불명이다() {
        HoldCommand command = new HoldCommand(7L, BillingReferenceType.LECTURE_ORDER_ITEM, 31L, 10_000L);
        respond(200, "application/json", "{\"statusCode\":200,\"data\":{\"referenceType\":");
        assertUnknown(() -> client().hold(command), 200, null);

        respond(200, "application/json", "{\"statusCode\":200,\"message\":\"\",\"data\":null}");
        assertUnknown(() -> client().hold(command), 200, null);

        respond(204, null, null);
        assertUnknown(() -> client().hold(command), 204, null);
    }

    @Test
    void 지갑이_꺼져_있어_연결을_거부하면_재시도_가능이다() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        assertThatThrownBy(() -> client("http://127.0.0.1:" + closedPort)
                .hold(new HoldCommand(7L, BillingReferenceType.LECTURE_ORDER_ITEM, 31L, 10_000L)))
                .isInstanceOfSatisfying(BillingCallException.class, e -> {
                    assertThat(e.getKind()).isEqualTo(BillingCallFailureKind.RETRYABLE);
                    assertThat(e.getHttpStatus()).isZero();
                    assertThat(e).hasRootCauseInstanceOf(ConnectException.class);
                });
    }

    @Test
    void 응답이_read_timeout보다_늦으면_결과_불명이다() {
        respond(200, "application/json", """
                {"statusCode":200,"message":"","data":{
                  "referenceType":"LECTURE_ORDER_ITEM","referenceId":31,"heldHp":10000,"replayed":false}}""");
        delayMillis = 1_000L;

        assertThatThrownBy(() -> client().hold(
                new HoldCommand(7L, BillingReferenceType.LECTURE_ORDER_ITEM, 31L, 10_000L)))
                .isInstanceOfSatisfying(BillingCallException.class, e -> {
                    assertThat(e.getKind()).isEqualTo(BillingCallFailureKind.UNKNOWN_RESULT);
                    assertThat(e.getHttpStatus()).isZero();
                    assertThat(e).hasRootCauseInstanceOf(SocketTimeoutException.class);
                });
    }

    @Test
    void 재시도_가능한_실패는_짧게_3번까지_보내고_마지막_예외를_던진다() {
        respond(429, "application/problem+json", """
                {"status":429,"detail":"요청이 너무 많습니다.","code":"TOO_MANY_REQUESTS"}""");

        assertRetryable(() -> client().hold(holdCommand()), 429, "TOO_MANY_REQUESTS");
        assertThat(requests).hasSize(3);
    }

    @Test
    void 재시도_중_성공하면_성공_결과를_돌려준다() {
        queue(new Response(408, null, null));
        respond(200, "application/json", HOLD_SUCCEEDED);

        assertThat(client().hold(holdCommand())).isInstanceOf(BillingCallResult.Succeeded.class);
        assertThat(requests).hasSize(2);
    }

    @Test
    void 결과_불명과_지갑이_응답한_실패는_다시_보내지_않는다() {
        respond(500, null, null);
        assertUnknown(() -> client().hold(holdCommand()), 500, null);
        assertThat(requests).hasSize(1);

        respond(409, "application/problem+json", """
                {"status":409,"detail":"HP 잔액이 부족합니다.","code":"INSUFFICIENT_BALANCE"}""");
        assertThat(client().hold(holdCommand())).isInstanceOf(BillingCallResult.Failed.class);
        assertThat(requests).hasSize(2);
    }

    @Test
    void 결과를_받지_못한_실패가_임계값을_넘으면_회로가_열리고_요청을_보내지_않는다() {
        BillingApiClient client = client(circuitBreaker(Duration.ofMinutes(1)));
        respond(500, null, null);
        assertUnknown(() -> client.hold(holdCommand()), 500, null);
        assertUnknown(() -> client.hold(holdCommand()), 500, null);
        assertThat(client.isCircuitOpen()).isTrue();

        // 요청을 보내지 않았으니 처리되지 않은 것이 확실하다 (D66). 짧은 Retry도 다시 보내지 않는다
        assertRetryable(() -> client.hold(holdCommand()), 0, BillingApiClient.CIRCUIT_OPEN);
        assertThat(requests).hasSize(2);
    }

    @Test
    void 열린_회로는_호출이_없어도_대기_뒤_시험_중이_되고_시험_호출이_성공하면_닫힌다() {
        BillingApiClient client = client(circuitBreaker(Duration.ofMillis(100)));
        respond(503, null, null);
        assertUnknown(() -> client.hold(holdCommand()), 503, null);
        assertUnknown(() -> client.hold(holdCommand()), 503, null);
        assertThat(client.isCircuitOpen()).isTrue();

        // 배치는 열린 회로에 호출하지 않으므로(D67) 호출 없이 넘어가야 한다
        await(() -> client.circuitBreaker().getState() == CircuitBreaker.State.HALF_OPEN);
        assertThat(client.isCircuitOpen()).isFalse();

        respond(200, "application/json", HOLD_SUCCEEDED);
        assertThat(client.hold(holdCommand())).isInstanceOf(BillingCallResult.Succeeded.class);

        assertThat(client.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void 지갑이_응답한_실패와_429와_락_대기_초과로는_회로가_열리지_않는다() {
        BillingApiClient client = client(circuitBreaker(Duration.ofMinutes(1)));
        respond(409, "application/problem+json", """
                {"status":409,"detail":"HP 잔액이 부족합니다.","code":"INSUFFICIENT_BALANCE"}""");
        client.hold(holdCommand());
        client.hold(holdCommand());

        respond(429, null, null);
        assertRetryable(() -> client.hold(holdCommand()), 429, null);

        respond(503, "application/problem+json", """
                {"status":503,"detail":"잠시 후 다시 시도해 주세요.","code":"HP_LOCK_TIMEOUT"}""");
        assertUnknown(() -> client.hold(holdCommand()), 503, "HP_LOCK_TIMEOUT");
        assertUnknown(() -> client.hold(holdCommand()), 503, "HP_LOCK_TIMEOUT");

        assertThat(client.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(client.circuitBreaker().getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void 시도가_시간_상한을_넘으면_결과_불명이다() {
        respond(200, "application/json", HOLD_SUCCEEDED);
        delayMillis = 1_000L;
        BillingApiClient client = new BillingApiClient(RestClient.builder(),
                new BillingClientProperties(baseUrl(), Duration.ofMillis(500), Duration.ofSeconds(5),
                        new BillingClientProperties.Resilience(null, FAST_RETRY, Duration.ofMillis(200))),
                API_KEY);

        long started = System.nanoTime();
        assertThatThrownBy(() -> client.hold(holdCommand()))
                .isInstanceOfSatisfying(BillingCallException.class, e -> {
                    assertThat(e.getKind()).isEqualTo(BillingCallFailureKind.UNKNOWN_RESULT);
                    assertThat(e.getHttpStatus()).isZero();
                    assertThat(e).hasRootCauseInstanceOf(TimeoutException.class);
                });
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(900));
    }

    private static void assertUnknown(Runnable call, int status, String rawCode) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BillingCallException.class, e -> {
            assertThat(e.getKind()).isEqualTo(BillingCallFailureKind.UNKNOWN_RESULT);
            assertThat(e.getHttpStatus()).isEqualTo(status);
            assertThat(e.getRawCode()).isEqualTo(rawCode);
        });
    }

    private void respond(int status, String contentType, String body) {
        this.status = status;
        this.contentType = contentType;
        this.body = body;
    }

    private void queue(Response response) {
        queued.addLast(response);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private BillingApiClient client() {
        return client(baseUrl());
    }

    private BillingApiClient client(BillingClientProperties.CircuitBreaker circuitBreaker) {
        return client(baseUrl(), circuitBreaker);
    }

    private static BillingApiClient client(String baseUrl) {
        return client(baseUrl, null);
    }

    private static BillingApiClient client(String baseUrl, BillingClientProperties.CircuitBreaker circuitBreaker) {
        return new BillingApiClient(RestClient.builder(), new BillingClientProperties(baseUrl, Duration.ofMillis(500),
                Duration.ofMillis(300), new BillingClientProperties.Resilience(circuitBreaker, FAST_RETRY, null)),
                API_KEY);
    }

    /** 호출 2번 중 절반이 실패하면 열리는 작은 회로. 시험 호출은 1번이다 */
    private static BillingClientProperties.CircuitBreaker circuitBreaker(Duration openDuration) {
        return new BillingClientProperties.CircuitBreaker(4, 2, 50f, null, null, openDuration, 1);
    }

    private static HoldCommand holdCommand() {
        return new HoldCommand(7L, BillingReferenceType.LECTURE_ORDER_ITEM, 31L, 10_000L);
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("2초 안에 조건이 맞아야 한다").isLessThan(deadline);
            sleep(20);
        }
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 가짜 서버가 받은 요청 */
    private record Request(String method, String path, String apiKey, String contentType, String body) {
    }

    /** 가짜 서버가 보낼 응답 */
    private record Response(int status, String contentType, String body) {
    }
}
