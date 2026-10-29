package com.hapbang.shared.appearance.out;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.hapbang.shared.appearance.domain.AppearanceEarningStatus;
import com.hapbang.shared.appearance.domain.AppearanceEarningType;
import com.hapbang.shared.appearance.dto.AppearanceEarningAmountDto;
import com.hapbang.shared.appearance.dto.AppearanceEarningPageDto;
import com.hapbang.shared.appearance.dto.AppearanceEarningSummaryDto;
import com.hapbang.shared.internal.InternalApiHeaders;
import com.sun.net.httpserver.HttpServer;

// 출연 내부 API Client의 요청 모양·응답 해제·오류 변환을 가짜 HTTP 서버로 확인한다 (#301, 강의 LectureApiClientTest와 같은 범위).
// 실제 출연 API는 apps 통합 테스트가 부른다.
class AppearanceApiClientTest {

    private static final String API_KEY = "unit-internal-api-key";

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile int status;
    private volatile String contentType;
    private volatile String body;
    private volatile long delayMillis;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestURI() + " key=" + exchange.getRequestHeaders()
                    .getFirst(InternalApiHeaders.API_KEY));
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
    void 요약_조회는_userId와_내부_키_헤더를_붙여_보내고_data를_돌려준다() {
        respond(200, "application/json", """
                {"statusCode":200,"message":"출연 수익 요약을 조회했습니다.","data":{
                  "confirmationPending":{"amountHp":20000,"count":1},
                  "scheduled":{"amountHp":0,"count":0},
                  "onHold":{"amountHp":21000,"count":1},
                  "processing":{"amountHp":9000,"count":1},
                  "failed":{"amountHp":23000,"count":1}}}""");

        AppearanceEarningSummaryDto summary = client().getEarningSummary(7L);
        client().getEarningSummary(7L, AppearanceEarningType.APPEARANCE_PENALTY);

        assertThat(requests).containsExactly(
                "/api/v1/internal/appearances/earnings/summary?userId=7 key=" + API_KEY,
                "/api/v1/internal/appearances/earnings/summary?userId=7&earningType=APPEARANCE_PENALTY key=" + API_KEY);
        assertThat(summary.confirmationPending()).isEqualTo(new AppearanceEarningAmountDto(20_000L, 1));
        assertThat(summary.scheduled()).isEqualTo(AppearanceEarningAmountDto.ZERO);
        assertThat(summary.onHold()).isEqualTo(new AppearanceEarningAmountDto(21_000L, 1));
        assertThat(summary.processing()).isEqualTo(new AppearanceEarningAmountDto(9_000L, 1));
        assertThat(summary.failed()).isEqualTo(new AppearanceEarningAmountDto(23_000L, 1));
    }

    @Test
    void 목록_조회는_상태_여러_개와_페이지를_쿼리로_보낸다() {
        respond(200, "application/json", """
                {"statusCode":200,"message":"출연 수익 목록을 조회했습니다.","data":{
                  "content":[{"sourceType":"APPEARANCE","sourceId":31,"earningType":"APPEARANCE_PENALTY",
                    "title":"합방 출연 요청","amountHp":9000,"status":"FAILED",
                    "statusUpdatedAt":"2026-11-04T01:00:00.123456"}],
                  "page":{"size":20,"number":1,"totalElements":21,"totalPages":2}}}""");

        AppearanceEarningPageDto page = client().getEarnings(7L,
                List.of(AppearanceEarningStatus.PROCESSING, AppearanceEarningStatus.FAILED),
                AppearanceEarningType.APPEARANCE_PENALTY, 1, 20);

        assertThat(requests).containsExactly("/api/v1/internal/appearances/earnings?userId=7&status=PROCESSING"
                + "&status=FAILED&earningType=APPEARANCE_PENALTY&page=1&size=20 key=" + API_KEY);
        assertThat(page.page()).isEqualTo(new AppearanceEarningPageDto.PageMetadata(20, 1, 21, 2));
        assertThat(page.content()).singleElement().satisfies(item -> {
            assertThat(item.sourceType()).isEqualTo("APPEARANCE");
            assertThat(item.sourceId()).isEqualTo(31L);
            assertThat(item.earningType()).isEqualTo(AppearanceEarningType.APPEARANCE_PENALTY);
            assertThat(item.status()).isEqualTo(AppearanceEarningStatus.FAILED);
            // 응답에서 빠진 null 필드는 null이다
            assertThat(item.payoutScheduledAt()).isNull();
            // offset 없는 KST 시각 그대로 읽는다
            assertThat(item.statusUpdatedAt()).isEqualTo(LocalDateTime.parse("2026-11-04T01:00:00.123456"));
        });
    }

    @Test
    void 지급_예정_시각은_필드가_빠져도_null로_와도_null로_읽는다() {
        String item = """
                {"sourceType":"APPEARANCE","sourceId":31,"earningType":"APPEARANCE_FEE",
                 "title":"합방 출연 요청","amountHp":20000,"status":"CONFIRMATION_PENDING",%s
                 "statusUpdatedAt":"2026-11-04T01:00:00"}""";
        String page = """
                {"statusCode":200,"message":"","data":{"content":[%s, %s],
                 "page":{"size":20,"number":0,"totalElements":2,"totalPages":1}}}""";
        respond(200, "application/json", page.formatted(item.formatted(""), item.formatted("\"payoutScheduledAt\":null,")));

        assertThat(client().getEarnings(7L, null, 0, 20).content())
                .hasSize(2)
                .allSatisfy(earning -> assertThat(earning.payoutScheduledAt()).isNull());
    }

    @Test
    void 상태를_null로_주면_상태_조건_없이_보낸다() {
        respond(200, "application/json", """
                {"statusCode":200,"message":"","data":{"content":[],
                 "page":{"size":20,"number":0,"totalElements":0,"totalPages":0}}}""");

        assertThat(client().getEarnings(7L, null, 0, 20).content()).isEmpty();
        assertThat(requests).containsExactly("/api/v1/internal/appearances/earnings?userId=7&page=0&size=20 key=" + API_KEY);
    }

    @Test
    void ProblemDetail_오류는_code를_담은_예외로_바뀐다() {
        respond(400, "application/problem+json", """
                {"status":400,"detail":"요청 값이 올바르지 않습니다.","code":"INVALID_REQUEST",
                 "errors":[{"field":"status","message":"값이 올바르지 않습니다."}]}""");

        assertThatThrownBy(() -> client().getEarnings(7L, List.of(), 0, 20))
                .isInstanceOfSatisfying(AppearanceApiCallException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(AppearanceApiCallException.Reason.REJECTED);
                    assertThat(e.getStatus()).isEqualTo(400);
                    assertThat(e.getCode()).isEqualTo("INVALID_REQUEST");
                });

        // 내부 API 키 오류는 본문이 없다
        respond(401, null, null);
        assertThatThrownBy(() -> client().getEarningSummary(7L))
                .isInstanceOfSatisfying(AppearanceApiCallException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(AppearanceApiCallException.Reason.REJECTED);
                    assertThat(e.getStatus()).isEqualTo(401);
                    assertThat(e.getCode()).isNull();
                });
    }

    @Test
    void 연결_실패와_timeout과_5xx는_호출_불가_예외로_바뀐다() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        AppearanceApiClient refused = client("http://127.0.0.1:" + closedPort);
        assertUnavailable(() -> refused.getEarningSummary(7L), 0);

        respond(200, "application/json", "{\"statusCode\":200,\"message\":\"\",\"data\":null}");
        delayMillis = 1_000L;
        assertUnavailable(() -> client().getEarningSummary(7L), 0);

        delayMillis = 0;
        respond(503, null, null);
        assertUnavailable(() -> client().getEarningSummary(7L), 503);
    }

    @Test
    void 읽을_수_없는_성공_응답은_호출_불가_예외로_바뀐다() {
        respond(200, "application/json", "{\"statusCode\":200,\"data\":{\"content\":[");
        assertUnavailable(() -> client().getEarnings(7L, List.of(), 0, 20), 200);

        // 모르는 상태 문자열(계약에 없는 값)
        respond(200, "application/json", """
                {"statusCode":200,"message":"","data":{"content":[{"sourceId":1,"status":"NEW_STATUS"}],
                 "page":{"size":20,"number":0,"totalElements":1,"totalPages":1}}}""");
        assertUnavailable(() -> client().getEarnings(7L, List.of(), 0, 20), 200);

        respond(204, null, null);
        assertUnavailable(() -> client().getEarningSummary(7L), 204);
    }

    @Test
    void 필수_필드가_빠진_성공_응답은_호출_불가_예외로_바뀐다() {
        respond(200, "application/json", "{\"statusCode\":200,\"message\":\"\",\"data\":{}}");
        assertUnavailable(() -> client().getEarningSummary(7L), 200);
        assertUnavailable(() -> client().getEarnings(7L, List.of(), 0, 20), 200);

        respond(200, "application/json", """
                {"statusCode":200,"message":"","data":{"content":[{}],
                 "page":{"size":20,"number":0,"totalElements":1,"totalPages":1}}}""");
        assertUnavailable(() -> client().getEarnings(7L, List.of(), 0, 20), 200);
    }

    @Test
    void 서버_오류의_ProblemDetail_code도_예외에_담는다() {
        respond(500, "application/problem+json", "{\"status\":500,\"code\":\"INTERNAL_ERROR\"}");

        assertThatThrownBy(() -> client().getEarningSummary(7L))
                .isInstanceOfSatisfying(AppearanceApiCallException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(AppearanceApiCallException.Reason.UNAVAILABLE);
                    assertThat(e.getStatus()).isEqualTo(500);
                    assertThat(e.getCode()).isEqualTo("INTERNAL_ERROR");
                });
    }

    private static void assertUnavailable(Runnable call, int status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(AppearanceApiCallException.class, e -> {
            assertThat(e.getReason()).isEqualTo(AppearanceApiCallException.Reason.UNAVAILABLE);
            assertThat(e.getStatus()).isEqualTo(status);
        });
    }

    private void respond(int status, String contentType, String body) {
        this.status = status;
        this.contentType = contentType;
        this.body = body;
    }

    private AppearanceApiClient client() {
        return client("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static AppearanceApiClient client(String baseUrl) {
        return new AppearanceApiClient(RestClient.builder(),
                new AppearanceClientProperties(baseUrl, Duration.ofMillis(500), Duration.ofMillis(300)), API_KEY);
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
}
