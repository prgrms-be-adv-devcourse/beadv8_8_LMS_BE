package com.hapbang.coupon.out.lecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.hapbang.coupon.app.port.LectureCatalog.LectureSnapshot;
import com.hapbang.coupon.app.port.LectureCatalog.LectureSummary;
import com.hapbang.coupon.app.port.LectureCatalogUnavailableException;
import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.shared.lecture.dto.LectureLookupDto;
import com.sun.net.httpserver.HttpServer;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;

// 강의 조회 내부 API(하나·일괄)를 부르는 쿠폰 HTTP Interface와 어댑터의 요청 모양·응답 해제·오류 변환·circuit breaker를 가짜 HTTP 서버로 확인한다
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

        // 최근 2번이 실패로 세졌다면 회로가 열려 다음 호출이 HTTP에 닿지 않는다
        respond(404, """
                {"status":404,"detail":"강의를 찾을 수 없습니다.","code":"LECTURE_NOT_FOUND"}""");
        assertThat(catalog.findLecture(99L)).isEmpty();
        assertThat(requests).hasSize(4);
    }

    // 일괄 조회 ---------------------------------------------------------------------------------------------

    @Test
    void 일괄_조회는_중복을_빼고_ids를_여러_번_보내며_없는_강의는_빼고_제목과_판매_상태를_돌려준다() {
        respond(200, """
                {"statusCode":200,"message":"강의를 조회했습니다.",
                 "data":{"10":{"lectureId":10,"sellerId":3,"price":15000,"saleStatus":"ON_SALE","title":"방송 장비 세팅"},
                         "11":null,
                         "12":{"lectureId":12,"sellerId":3,"price":9000,"saleStatus":"PENDING_INSPECTION","title":"검수 중"}}}""");

        assertThat(catalog(baseUrl()).findLectures(List.of(10L, 11L, 12L, 10L))).isEqualTo(Map.of(
                10L, new LectureSummary(10L, "방송 장비 세팅", "ON_SALE"),
                12L, new LectureSummary(12L, "검수 중", "PENDING_INSPECTION")));
        assertThat(requests).containsExactly("GET /api/v1/internal/lectures?ids=10&ids=11&ids=12 key=" + API_KEY);
    }

    @Test
    void 일괄_조회는_100개씩_나눠_차례로_부른다() {
        List<Long> ids = LongStream.rangeClosed(1, LectureLookupDto.MAX_BATCH_SIZE + 1).boxed().toList();
        respond(200, "{\"statusCode\":200,\"message\":\"\",\"data\":{" + ids.stream()
                .map(id -> "\"" + id + "\":null").collect(Collectors.joining(",")) + "}}");

        assertThat(catalog(baseUrl()).findLectures(ids)).isEmpty();
        assertThat(requests).hasSize(2);
        assertThat(requests.getFirst()).contains("ids=1&", "&ids=100 ").doesNotContain("ids=101");
        assertThat(requests.getLast()).isEqualTo("GET /api/v1/internal/lectures?ids=101 key=" + API_KEY);
    }

    @Test
    void 일괄_응답에_요청한_강의_키가_없거나_data가_없거나_오류_상태면_조회_불가다() {
        // 요청한 11이 빠졌다: 없는 강의로 보지 않는다
        respond(200, """
                {"statusCode":200,"message":"",
                 "data":{"10":{"lectureId":10,"sellerId":3,"price":15000,"saleStatus":"ON_SALE","title":"방송 장비 세팅"}}}""");
        assertBatchUnavailable();

        respond(200, "{\"statusCode\":200,\"message\":\"\"}");
        assertBatchUnavailable();

        respond(200, "깨진 JSON");
        assertBatchUnavailable();

        // 일괄 조회는 강의 없음을 null 값으로 답하므로 404 LECTURE_NOT_FOUND도 계약에 없는 응답이다
        respond(404, """
                {"status":404,"detail":"강의를 찾을 수 없습니다.","code":"LECTURE_NOT_FOUND"}""");
        assertBatchUnavailable();

        respond(500, "{\"status\":500,\"code\":\"INTERNAL_ERROR\"}");
        assertBatchUnavailable();
    }

    @Test
    void 일괄_조회는_따로_회로를_써서_열리면_일괄만_HTTP를_부르지_않고_하나_조회는_그대로_부른다() {
        HttpLectureCatalog catalog = catalog(baseUrl(), smallWindow());
        respond(500, "{\"status\":500,\"code\":\"INTERNAL_ERROR\"}");
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> catalog.findLectures(List.of(1L)))
                    .isInstanceOf(LectureCatalogUnavailableException.class);
        }
        int sent = requests.size();

        assertThatThrownBy(() -> catalog.findLectures(List.of(1L)))
                .isInstanceOf(LectureCatalogUnavailableException.class)
                .hasCauseInstanceOf(CallNotPermittedException.class);
        assertThat(requests).hasSize(sent);

        // 주문의 쿠폰 계산·쿠폰 발행이 쓰는 하나 조회는 일괄 회로가 열려도 강의 앱을 부른다
        respond(200, """
                {"statusCode":200,"message":"강의를 조회했습니다.",
                 "data":{"lectureId":1,"sellerId":3,"price":15000,"saleStatus":"ON_SALE","title":"방송 장비 세팅"}}""");
        assertThat(catalog.findLecture(1L)).contains(new LectureSnapshot(1L, 3L, 15_000L, "ON_SALE"));
        assertThat(requests).hasSize(sent + 1);
    }

    private void assertBatchUnavailable() {
        assertThatThrownBy(() -> catalog(baseUrl()).findLectures(List.of(10L, 11L)))
                .isInstanceOf(LectureCatalogUnavailableException.class);
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
        return catalog(baseUrl,
                new CouponLectureClientProperties(READ_TIMEOUT, READ_TIMEOUT, null, null, null, null, null, null, null));
    }

    // 최근 2번 중 2번 모두 실패하면 열리고, 테스트 동안 다시 HALF_OPEN이 되지 않는 회로
    private static CouponLectureClientProperties smallWindow() {
        return new CouponLectureClientProperties(READ_TIMEOUT, READ_TIMEOUT, 2, 2, null, null, null,
                Duration.ofMinutes(5), null);
    }

    /** 운영 빈과 같은 방법(설정의 제한 시간·내부 API 키)으로 만든 프록시에 어댑터를 붙인다 */
    private static HttpLectureCatalog catalog(String baseUrl, CouponLectureClientProperties properties) {
        CouponLectureInternalApi api = new CouponLectureApiConfig().couponLectureInternalApi(RestClient.builder(),
                baseUrl, API_KEY, properties);
        return new HttpLectureCatalog(api, properties);
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
