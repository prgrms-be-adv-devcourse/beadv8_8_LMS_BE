package com.hapbang.shared.lecture.out;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.shared.lecture.domain.LectureEarningStatus;
import com.hapbang.shared.lecture.domain.LectureEarningType;
import com.hapbang.shared.lecture.dto.LectureEarningAmountDto;
import com.hapbang.shared.lecture.dto.LectureEarningPageDto;
import com.hapbang.shared.lecture.dto.LectureEarningSummaryDto;
import com.sun.net.httpserver.HttpServer;

// 강의 내부 API Client의 요청 모양·응답 해제·오류 변환을 가짜 HTTP 서버로 확인한다 (#300). 실제 강의 API는 apps 통합 테스트가 부른다.
class LectureApiClientTest {

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
                {"statusCode":200,"message":"판매 수익 요약을 조회했습니다.","data":{
                  "confirmationPending":{"amountHp":10000,"count":1},
                  "scheduled":{"amountHp":20000,"count":2},
                  "onHold":{"amountHp":0,"count":0},
                  "processing":{"amountHp":0,"count":0},
                  "failed":{"amountHp":5000,"count":1},
                  "asOf":"2026-11-05T12:00:00+09:00"}}""");

        LectureEarningSummaryDto summary = client().getEarningSummary(7L);

        assertThat(requests).containsExactly(
                "/api/v1/internal/lectures/earnings/summary?userId=7 key=" + API_KEY);
        assertThat(summary.confirmationPending()).isEqualTo(new LectureEarningAmountDto(10_000L, 1));
        assertThat(summary.scheduled()).isEqualTo(new LectureEarningAmountDto(20_000L, 2));
        assertThat(summary.failed()).isEqualTo(new LectureEarningAmountDto(5_000L, 1));
        assertThat(summary.asOf()).isEqualTo(OffsetDateTime.parse("2026-11-05T12:00:00+09:00"));
    }

    @Test
    void 목록_조회는_상태_여러_개와_페이지를_쿼리로_보낸다() {
        respond(200, "application/json", """
                {"statusCode":200,"message":"판매 수익 목록을 조회했습니다.","data":{
                  "content":[{"sourceType":"LECTURE_ORDER_ITEM","sourceId":31,"earningType":"LECTURE_SALE",
                    "title":"방송 장비 세팅 강의","amountHp":10000,"status":"FAILED",
                    "statusUpdatedAt":"2026-11-04T01:00:00+09:00"}],
                  "page":{"size":20,"number":1,"totalElements":21,"totalPages":2}}}""");

        LectureEarningPageDto page = client().getEarnings(7L,
                List.of(LectureEarningStatus.SCHEDULED, LectureEarningStatus.FAILED), 1, 20);

        assertThat(requests).containsExactly("/api/v1/internal/lectures/earnings?userId=7&status=SCHEDULED"
                + "&status=FAILED&page=1&size=20 key=" + API_KEY);
        assertThat(page.page()).isEqualTo(new LectureEarningPageDto.PageMetadata(20, 1, 21, 2));
        assertThat(page.content()).singleElement().satisfies(item -> {
            assertThat(item.sourceId()).isEqualTo(31L);
            assertThat(item.earningType()).isEqualTo(LectureEarningType.LECTURE_SALE);
            assertThat(item.status()).isEqualTo(LectureEarningStatus.FAILED);
            // 응답에서 빠진 null 필드는 null이다
            assertThat(item.payoutScheduledAt()).isNull();
            assertThat(item.statusUpdatedAt()).isEqualTo(OffsetDateTime.parse("2026-11-04T01:00:00+09:00"));
        });
    }

    @Test
    void ProblemDetail_오류는_code를_담은_예외로_바뀐다() {
        respond(400, "application/problem+json", """
                {"status":400,"detail":"요청 값이 올바르지 않습니다.","code":"INVALID_REQUEST",
                 "errors":[{"field":"status","message":"값이 올바르지 않습니다."}]}""");

        assertThatThrownBy(() -> client().getEarnings(7L, List.of(), 0, 20))
                .isInstanceOfSatisfying(LectureApiCallException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(LectureApiCallException.Reason.REJECTED);
                    assertThat(e.getStatus()).isEqualTo(400);
                    assertThat(e.getCode()).isEqualTo("INVALID_REQUEST");
                });

        // 내부 API 키 오류는 본문이 없다
        respond(401, null, null);
        assertThatThrownBy(() -> client().getEarningSummary(7L))
                .isInstanceOfSatisfying(LectureApiCallException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(LectureApiCallException.Reason.REJECTED);
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
        LectureApiClient refused = client("http://127.0.0.1:" + closedPort);
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

    private static void assertUnavailable(Runnable call, int status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(LectureApiCallException.class, e -> {
            assertThat(e.getReason()).isEqualTo(LectureApiCallException.Reason.UNAVAILABLE);
            assertThat(e.getStatus()).isEqualTo(status);
        });
    }

    private void respond(int status, String contentType, String body) {
        this.status = status;
        this.contentType = contentType;
        this.body = body;
    }

    private LectureApiClient client() {
        return client("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static LectureApiClient client(String baseUrl) {
        return new LectureApiClient(RestClient.builder(),
                new LectureClientProperties(baseUrl, Duration.ofMillis(500), Duration.ofMillis(300)), API_KEY);
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
