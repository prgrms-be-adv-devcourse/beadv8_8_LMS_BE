package com.hapbang.coupon.out.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.hapbang.coupon.app.port.PromotionBudget.BudgetResult;
import com.hapbang.coupon.app.port.PromotionBudget.Outcome;
import com.hapbang.coupon.app.port.PromotionBudget.WorkMetadata;
import com.hapbang.coupon.domain.BudgetWorkKind;
import com.hapbang.shared.billing.out.BillingClientProperties;
import com.hapbang.shared.internal.InternalApiHeaders;
import com.sun.net.httpserver.HttpServer;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 실제 HTTP 프록시의 헤더·shared 계약·결과 분류·응답 유실·회로를 작은 가짜 서버로 확인한다. Spring 컨텍스트는 만들지 않는다.
class HttpPromotionBudgetTest {

    private static final String API_KEY = "unit-budget-api-key";
    private static final Duration TIMEOUT = Duration.ofMillis(400);
    private static final String RESERVE = "coupon-42-reserve-v1";
    private static final String RELEASE = "coupon-42-release-member-901";
    private static final String PATH = "/api/v1/internal/billing/promotion-budgets";
    private static final String RESERVED = """
            {"statusCode":200,"message":"예약했습니다.","data":{
            "workId":"coupon-42-reserve-v1","reservationId":"PB-7","couponId":42,"amountHp":10000,"replayed":false}}
            """;
    private static final String RELEASED = """
            {"statusCode":200,"message":"해제했습니다.","data":{
            "workId":"coupon-42-release-member-901","reservationId":"PB-7","releasedHp":300,
            "remainingHp":9700,"replayed":false}}
            """;
    private static final String RESERVATION = """
            {"data":{"reservationId":"PB-7","couponId":42,"amountHp":10000,
            "remainingHp":10000,"heldHp":0,"paidHp":0,"releasedHp":0}}
            """;

    private HttpServer server;
    private ExecutorService executor;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final Map<String, Response> responses = new ConcurrentHashMap<>();
    private volatile int status;
    private volatile String responseBody;
    private volatile long delayMillis;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders().getFirst(InternalApiHeaders.API_KEY),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            try {
                if (delayMillis > 0) {
                    Thread.sleep(delayMillis);
                }
                Response response = responses.getOrDefault(exchange.getRequestURI().getPath(),
                        new Response(status, responseBody));
                byte[] bytes = response.body() == null ? new byte[0] : response.body().getBytes(StandardCharsets.UTF_8);
                if (bytes.length > 0) {
                    exchange.getResponseHeaders().add("Content-Type",
                            response.status() >= 400 ? "application/problem+json" : "application/json");
                }
                exchange.sendResponseHeaders(response.status(), bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    exchange.getResponseBody().write(bytes);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void 예약은_내부_키와_shared_본문으로_보내고_검증된_예약_ID를_돌려준다() {
        respond(200, RESERVED);
        assertThat(adapter().reserve(RESERVE, 42L, 1, 10_000L))
                .isEqualTo(new BudgetResult(Outcome.SUCCEEDED, "PB-7", null));
        Request sent = requests.getFirst();
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.path()).isEqualTo(PATH + "/reservations");
        assertThat(sent.key()).isEqualTo(API_KEY);
        assertThat(json(sent.body())).isEqualTo(json("""
                {"workId":"coupon-42-reserve-v1","couponId":42,"policyVersion":1,"amountHp":10000}
                """));
    }

    @Test
    void 해제는_예약_ID와_해제_대상_사유를_같은_요청에_싣는다() {
        respond(200, RELEASED);
        assertThat(adapter().release(RELEASE, "PB-7", 300L, "MEMBER_COUPON:901", "만료"))
                .isEqualTo(new BudgetResult(Outcome.SUCCEEDED, "PB-7", null));
        Request sent = requests.getFirst();
        assertThat(sent.path()).isEqualTo(PATH + "/releases");
        assertThat(sent.key()).isEqualTo(API_KEY);
        assertThat(json(sent.body())).isEqualTo(json("""
                {"workId":"coupon-42-release-member-901","reservationId":"PB-7","amountHp":300,
                "target":"MEMBER_COUPON:901","reason":"만료"}
                """));
    }

    @ParameterizedTest
    @ValueSource(strings = {"RESERVE", "RELEASE"})
    void 작업_조회는_DONE과_예약_소유_쿠폰을_확인해_작업_대조_근거를_돌려준다(String kind) {
        respond(200, """
                {"statusCode":200,"message":"조회했습니다.","data":{"workId":"coupon-42-reserve-v1",
                "kind":"%s","status":"DONE","reservationId":"PB-7","amountHp":10000}}
                """.formatted(kind));
        respondAt(PATH + "/reservations/PB-7", 200, RESERVATION);
        assertThat(adapter().status(RESERVE)).isEqualTo(new BudgetResult(Outcome.SUCCEEDED, "PB-7", null,
                new WorkMetadata(RESERVE, BudgetWorkKind.valueOf(kind), 42L, 10_000L)));
        assertThat(requests.getFirst()).isEqualTo(new Request("GET", PATH + "/works/" + RESERVE, API_KEY, ""));
        assertThat(requests.getLast()).isEqualTo(new Request("GET", PATH + "/reservations/PB-7", API_KEY, ""));
    }

    @Test
    void 작업이_없으면_UNKNOWN이고_같은_작업_ID로_예약을_다시_보낼_수_있다() {
        HttpPromotionBudget adapter = adapter();
        respond(200, """
                {"statusCode":200,"data":{"workId":"coupon-42-reserve-v1","status":"NOT_FOUND"}}
                """);
        assertThat(adapter.status(RESERVE)).isEqualTo(new BudgetResult(Outcome.UNKNOWN, null, "NOT_FOUND"));
        respond(200, RESERVED);
        assertThat(adapter.reserve(RESERVE, 42L, 1, 10_000L).outcome()).isEqualTo(Outcome.SUCCEEDED);
        assertThat(requests).hasSize(2);
        assertThat(json(requests.getLast().body()).get("workId").asString()).isEqualTo(RESERVE);
    }

    @ParameterizedTest
    @CsvSource({"409,PROMOTION_BALANCE_INSUFFICIENT", "409,BUDGET_RELEASE_EXCEEDS_REMAINING",
            "404,BUDGET_RESERVATION_NOT_FOUND", "409,IDEMPOTENCY_KEY_CONFLICT", "400,INVALID_POINT_AMOUNT"})
    void 업무_4xx는_거절_코드를_보존하고_회로_실패로_세지_않는다(int status, String code) {
        HttpPromotionBudget adapter = adapter();
        respond(status, "{\"status\":" + status + ",\"code\":\"" + code + "\"}");
        assertThat(adapter.reserve(RESERVE, 42L, 1, 10_000L))
                .isEqualTo(new BudgetResult(Outcome.REJECTED, null, code));
        assertThat(adapter.circuitBreaker().getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"408,IDEMPOTENCY_KEY_CONFLICT", "425,IDEMPOTENCY_KEY_CONFLICT", "429,IDEMPOTENCY_KEY_CONFLICT",
            "409,BUDGET_RESERVATION_EXISTS", "401,UNAUTHORIZED", "403,FORBIDDEN", "400,UNKNOWN_CODE",
            "500,INTERNAL_ERROR", "503,HP_LOCK_TIMEOUT"})
    void 일시_오류와_예약_존재_충돌_인증_오류는_새_업무_거절로_확정하지_않는다(int status, String code) {
        respond(status, "{\"status\":" + status + ",\"code\":\"" + code + "\"}");
        assertThat(adapter().reserve(RESERVE, 42L, 1, 10_000L).outcome()).isEqualTo(Outcome.UNKNOWN);
    }

    @Test
    void 오류_본문이_없거나_깨졌고_상태_조회가_4xx이면_UNKNOWN이다() {
        respond(404, null);
        assertThat(adapter().reserve(RESERVE, 42L, 1, 10_000L).outcome()).isEqualTo(Outcome.UNKNOWN);
        respond(400, "깨진 JSON");
        assertThat(adapter().reserve(RESERVE, 42L, 1, 10_000L).outcome()).isEqualTo(Outcome.UNKNOWN);
        respond(409, "{\"code\":\"IDEMPOTENCY_KEY_CONFLICT\"}");
        assertThat(adapter().status(RESERVE).outcome()).isEqualTo(Outcome.UNKNOWN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "깨진 JSON", "{\"statusCode\":200}",
            "{\"data\":{\"workId\":\"other\",\"couponId\":42,\"amountHp\":10000,\"reservationId\":\"PB-7\",\"replayed\":false}}",
            "{\"data\":{\"workId\":\"coupon-42-reserve-v1\",\"couponId\":41,\"amountHp\":10000,\"reservationId\":\"PB-7\",\"replayed\":false}}",
            "{\"data\":{\"workId\":\"coupon-42-reserve-v1\",\"couponId\":42,\"amountHp\":9999,\"reservationId\":\"PB-7\",\"replayed\":false}}",
            "{\"data\":{\"workId\":\"coupon-42-reserve-v1\",\"couponId\":42,\"amountHp\":10000,\"reservationId\":\"wrong\",\"replayed\":false}}"})
    void 성공_응답이_비거나_깨졌거나_다른_예약이면_UNKNOWN이다(String body) {
        respond(200, body);
        assertThat(adapter().reserve(RESERVE, 42L, 1, 10_000L).outcome()).isEqualTo(Outcome.UNKNOWN);
    }

    @Test
    void 해제_응답의_작업_예약_금액을_대조하고_음수_잔액을_거절한다() {
        for (String body : List.of(RELEASED.replace(RELEASE, "other"), RELEASED.replace("PB-7", "PB-8"),
                RELEASED.replace("\"releasedHp\":300", "\"releasedHp\":299"),
                RELEASED.replace("\"remainingHp\":9700", "\"remainingHp\":-1"))) {
            respond(200, body);
            assertThat(adapter().release(RELEASE, "PB-7", 300L, "MEMBER_COUPON:901", "만료").outcome())
                    .isEqualTo(Outcome.UNKNOWN);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"workId\":\"other\",\"status\":\"NOT_FOUND\"}",
            "{\"workId\":\"coupon-42-reserve-v1\",\"status\":\"DONE\",\"reservationId\":\"PB-7\",\"amountHp\":10000}",
            "{\"workId\":\"coupon-42-reserve-v1\",\"kind\":\"OTHER\",\"status\":\"DONE\",\"reservationId\":\"PB-7\",\"amountHp\":10000}",
            "{\"workId\":\"coupon-42-reserve-v1\",\"kind\":\"RESERVE\",\"status\":\"DONE\",\"reservationId\":\"wrong\",\"amountHp\":10000}",
            "{\"workId\":\"coupon-42-reserve-v1\",\"kind\":\"RESERVE\",\"status\":\"DONE\",\"reservationId\":\"PB-7\",\"amountHp\":0}",
            "{\"workId\":\"coupon-42-reserve-v1\",\"kind\":\"RESERVE\",\"status\":\"DONE\",\"reservationId\":\"PB-7\"}"})
    void 조회_결과가_요청과_맞지_않거나_DONE의_근거가_빠지면_UNKNOWN이다(String data) {
        respond(200, "{\"data\":" + data + "}");
        assertThat(adapter().status(RESERVE)).isEqualTo(new BudgetResult(Outcome.UNKNOWN, null, "RESPONSE_MISMATCH"));
        assertThat(requests).hasSize(1);
    }

    @Test
    void 조회한_예약의_ID_쿠폰_총액이_깨졌으면_조회_성공으로_확정하지_않는다() {
        respond(200, done("RESERVE", 10_000L));
        for (String body : List.of(RESERVATION.replace("PB-7", "PB-8"),
                RESERVATION.replace("\"couponId\":42", "\"couponId\":0"),
                RESERVATION.replace("\"couponId\":42,", ""),
                RESERVATION.replace("\"amountHp\":10000", "\"amountHp\":9999"))) {
            respondAt(PATH + "/reservations/PB-7", 200, body);
            assertThat(adapter().status(RESERVE)).isEqualTo(new BudgetResult(Outcome.UNKNOWN, null, "RESPONSE_MISMATCH"));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 503})
    void 완료_작업의_예약_소유_근거를_조회하지_못하면_불명_사유를_구분한다(int status) {
        respond(200, done("RESERVE", 10_000L));
        respondAt(PATH + "/reservations/PB-7", status, "{\"code\":\"BUDGET_RESERVATION_NOT_FOUND\"}");
        assertThat(adapter().status(RESERVE))
                .isEqualTo(new BudgetResult(Outcome.UNKNOWN, null, "STATUS_METADATA_UNAVAILABLE"));
    }

    @Test
    void 양수인_조회_내용도_영속_작업과_대조하도록_원래_메타데이터를_전달한다() {
        respond(200, done("RELEASE", 999L));
        respondAt(PATH + "/reservations/PB-7", 200, RESERVATION.replace("\"couponId\":42", "\"couponId\":43"));
        assertThat(adapter().status(RESERVE)).isEqualTo(new BudgetResult(Outcome.SUCCEEDED, "PB-7", null,
                new WorkMetadata(RESERVE, BudgetWorkKind.RELEASE, 43L, 999L)));
    }

    @Test
    void 연결_실패와_응답_시간_초과는_UNKNOWN으로_남긴다() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        assertThat(adapter("http://127.0.0.1:" + closedPort, defaults()).status(RESERVE).outcome())
                .isEqualTo(Outcome.UNKNOWN);
        respond(200, RESERVED);
        delayMillis = TIMEOUT.toMillis() * 3;
        assertThat(adapter().reserve(RESERVE, 42L, 1, 10_000L).outcome()).isEqualTo(Outcome.UNKNOWN);
    }

    @Test
    void 기술_실패가_쌓여_회로가_열리면_HTTP_호출_없이_UNKNOWN이다() {
        HttpPromotionBudget adapter = adapter(baseUrl(), smallWindow());
        respond(500, "{\"code\":\"INTERNAL_ERROR\"}");
        assertThat(adapter.reserve(RESERVE, 42L, 1, 10_000L).outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(adapter.reserve(RESERVE, 42L, 1, 10_000L).outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(adapter.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        int count = requests.size();
        assertThat(adapter.status(RESERVE)).isEqualTo(new BudgetResult(Outcome.UNKNOWN, null, "CIRCUIT_OPEN"));
        assertThat(requests).hasSize(count);
    }

    private HttpPromotionBudget adapter() {
        return adapter(baseUrl(), defaults());
    }

    private static HttpPromotionBudget adapter(String baseUrl, BillingClientProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(TIMEOUT);
        requestFactory.setReadTimeout(TIMEOUT);
        return new HttpPromotionBudget(CouponBillingApiConfig.create(RestClient.builder().requestFactory(requestFactory),
                baseUrl, API_KEY), properties);
    }

    private static BillingClientProperties defaults() {
        return new BillingClientProperties("http://unused", null, null);
    }

    private static BillingClientProperties smallWindow() {
        return new BillingClientProperties("http://unused", null, null,
                new BillingClientProperties.Resilience(new BillingClientProperties.CircuitBreaker(2, 2, 50f,
                        null, null, Duration.ofMinutes(5), null), null, null));
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void respond(int status, String body) {
        this.status = status;
        responseBody = body;
    }

    private void respondAt(String path, int status, String body) {
        responses.put(path, new Response(status, body));
    }

    private static String done(String kind, long amount) {
        return """
                {"data":{"workId":"coupon-42-reserve-v1","kind":"%s","status":"DONE",
                "reservationId":"PB-7","amountHp":%d}}
                """.formatted(kind, amount);
    }

    private static JsonNode json(String body) {
        return JsonMapper.shared().readTree(body);
    }

    private record Request(String method, String path, String key, String body) {
    }

    private record Response(int status, String body) {
    }
}
