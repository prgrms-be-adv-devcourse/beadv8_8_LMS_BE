package com.hapbang.coupon.out.lecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.hapbang.coupon.app.port.LectureCatalog.LectureSnapshot;
import com.hapbang.coupon.app.port.LectureCatalogUnavailableException;
import com.hapbang.shared.internal.InternalApiHeaders;
import com.sun.net.httpserver.HttpServer;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

// 강의 조회 내부 API를 부르는 쿠폰 HTTP Interface와 어댑터의 요청 모양·응답 해제·오류 변환·circuit breaker를 가짜 HTTP 서버로 확인한다
class HttpLectureCatalogTest {

    private static final String API_KEY = "unit-internal-api-key";
    private static final Duration READ_TIMEOUT = Duration.ofMillis(300);

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile int status;
    private volatile String body;
    private volatile long delayMillis;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " key="
                    + exchange.getRequestHeaders().getFirst(InternalApiHeaders.API_KEY));
            sleep(delayMillis);
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 0) {
                exchange.getResponseHeaders().add("Content-Type",
                        status >= 400 ? "application/problem+json" : "application/json");
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
    void 강의를_내부_키_헤더를_붙여_GET으로_읽고_data를_스냅숏으로_돌려준다() {
        respond(200, """
                {"statusCode":200,"message":"강의를 조회했습니다.",
                 "data":{"lectureId":10,"sellerId":3,"price":15000,"saleStatus":"ON_SALE"}}""");

        assertThat(catalog(baseUrl()).findLecture(10L))
                .contains(new LectureSnapshot(10L, 3L, 15_000L, "ON_SALE"));
        assertThat(requests).containsExactly("GET /api/v1/internal/lectures/10 key=" + API_KEY);
    }

    @Test
    void 강의_없음_404_LECTURE_NOT_FOUND는_빈_값이다() {
        respond(404, """
                {"status":404,"detail":"강의를 찾을 수 없습니다.","code":"LECTURE_NOT_FOUND"}""");

        assertThat(catalog(baseUrl()).findLecture(99L)).isEmpty();
    }

    @Test
    void 강의_없음이_아닌_오류_상태는_조회_불가다() {
        // 본문 없는 404: 내부 API를 외부 포트로 부른 설정 오류. 강의 없음으로 보지 않는다
        respond(404, null);
        assertUnavailable();

        // 내부 API 키 오류
        respond(401, null);
        assertUnavailable();

        respond(500, "{\"status\":500,\"code\":\"INTERNAL_ERROR\"}");
        assertUnavailable();
    }

    @Test
    void 성공_상태인데_data가_없거나_읽을_수_없으면_조회_불가다() {
        respond(200, "{\"statusCode\":200,\"message\":\"\"}");
        assertUnavailable();

        respond(200, "{\"statusCode\":200,\"message\":\"\",\"data\":{\"lectureId\":10}}");
        assertUnavailable();

        respond(200, "깨진 JSON");
        assertUnavailable();
    }

    @Test
    void 연결_실패와_응답_시간_초과는_조회_불가다() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        assertThatThrownBy(() -> catalog("http://127.0.0.1:" + closedPort).findLecture(1L))
                .isInstanceOf(LectureCatalogUnavailableException.class);

        respond(200, "{\"statusCode\":200,\"message\":\"\",\"data\":null}");
        delayMillis = READ_TIMEOUT.toMillis() * 3;
        assertUnavailable();
    }

    @Test
    void 실패가_쌓여_회로가_열리면_HTTP를_부르지_않고_조회_불가다() {
        HttpLectureCatalog catalog = catalog(baseUrl(), smallWindow());
        respond(500, "{\"status\":500,\"code\":\"INTERNAL_ERROR\"}");
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> catalog.findLecture(1L)).isInstanceOf(LectureCatalogUnavailableException.class);
        }
        assertThat(catalog.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        int sent = requests.size();

        assertThatThrownBy(() -> catalog.findLecture(1L))
                .isInstanceOf(LectureCatalogUnavailableException.class)
                .hasCauseInstanceOf(CallNotPermittedException.class);
        assertThat(requests).hasSize(sent);
    }

    @Test
    void 강의_없음과_4xx는_회로_실패로_세지_않는다() {
        HttpLectureCatalog catalog = catalog(baseUrl(), smallWindow());
        respond(404, """
                {"status":404,"detail":"강의를 찾을 수 없습니다.","code":"LECTURE_NOT_FOUND"}""");
        assertThat(catalog.findLecture(99L)).isEmpty();
        assertThat(catalog.findLecture(99L)).isEmpty();
        // 내부 API 키 오류: 호출한 쪽에는 조회 불가지만 강의 앱이 판단한 응답이다
        respond(401, null);
        assertThatThrownBy(() -> catalog.findLecture(1L)).isInstanceOf(LectureCatalogUnavailableException.class);

        assertThat(catalog.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(catalog.circuitBreaker().getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(requests).hasSize(3);
    }

    private void assertUnavailable() {
        assertThatThrownBy(() -> catalog(baseUrl()).findLecture(1L))
                .isInstanceOf(LectureCatalogUnavailableException.class);
    }

    private void respond(int status, String body) {
        this.status = status;
        this.body = body;
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static HttpLectureCatalog catalog(String baseUrl) {
        return catalog(baseUrl, CouponLectureClientProperties.defaults());
    }

    // 최근 2번 중 2번 모두 실패하면 열리고, 테스트 동안 다시 HALF_OPEN이 되지 않는 회로
    private static CouponLectureClientProperties smallWindow() {
        return new CouponLectureClientProperties(null, null, 2, 2, null, null, null, Duration.ofMinutes(5), null);
    }

    private static HttpLectureCatalog catalog(String baseUrl, CouponLectureClientProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(READ_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return new HttpLectureCatalog(CouponLectureApiConfig.create(
                RestClient.builder().requestFactory(requestFactory), baseUrl, API_KEY), properties);
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
