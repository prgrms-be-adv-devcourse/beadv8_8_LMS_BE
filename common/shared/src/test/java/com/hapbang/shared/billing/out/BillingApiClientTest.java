package com.hapbang.shared.billing.out;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

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
import com.hapbang.shared.internal.InternalApiHeaders;
import com.sun.net.httpserver.HttpServer;

// 지갑 내부 API Client의 요청 모양·응답 해제·실패 분류(D64)를 가짜 HTTP 서버로 확인한다 (#272). 실제 지갑 API는 #273이 확인한다.
class BillingApiClientTest {

    private static final String API_KEY = "unit-internal-api-key";

    private HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile int status;
    private volatile String contentType;
    private volatile String body;
    private volatile long delayMillis;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders().getFirst(InternalApiHeaders.API_KEY),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            sleep(delayMillis);
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            if (contentType != null) {
                exchange.getResponseHeaders().add("Content-Type", contentType);
            }
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
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

    private BillingApiClient client() {
        return client("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static BillingApiClient client(String baseUrl) {
        return new BillingApiClient(RestClient.builder(),
                new BillingClientProperties(baseUrl, Duration.ofMillis(500), Duration.ofMillis(300)), API_KEY);
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
}
