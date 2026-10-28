package com.hapbang.billing.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.TestLogin;
import com.sun.net.httpserver.HttpServer;

// 지급 예정 수익 통합 API(#295)를 컨트롤러부터 실제 shared Client까지 확인한다. 강의·출연 내부 API는 가짜 HTTP 서버가 대신한다.
// RsData·페이지 모양, Instant 시각, 로그인 회원 기준 조회, 0원 요약, page·size·status·깊이 검증, 출처 장애 503(ProblemDetail)을 본다.
@IntegrationTest
@AutoConfigureMockMvc
// 가짜 서버 주소(@DynamicPropertySource) 때문에 이 클래스만 쓰는 컨텍스트가 생긴다. 캐시에 남아 DB 연결을 잡지 않게 끝나면 닫는다
@DirtiesContext
class ApiV1SettlementEarningControllerTest {

    private static final String SUMMARY = "/api/v1/settlements/earnings/summary";
    private static final String LIST = "/api/v1/settlements/earnings";
    private static final String LECTURE_SUMMARY = "/api/v1/internal/lectures/earnings/summary";
    private static final String LECTURE_LIST = "/api/v1/internal/lectures/earnings";
    private static final String APPEARANCE_SUMMARY = "/api/v1/internal/appearances/earnings/summary";
    private static final String APPEARANCE_LIST = "/api/v1/internal/appearances/earnings";
    private static final String ZERO_BUCKET = "{\"amountHp\":0,\"count\":0}";
    private static final String EMPTY_PAGE =
            "{\"content\":[],\"page\":{\"size\":20,\"number\":0,\"totalElements\":0,\"totalPages\":0}}";

    private static final HttpServer SERVER = startServer();
    private static final Map<String, Response> RESPONSES = new ConcurrentHashMap<>();
    private static final List<String> REQUESTS = new CopyOnWriteArrayList<>();

    @Autowired
    MockMvc mockMvc;

    @DynamicPropertySource
    static void clients(DynamicPropertyRegistry registry) {
        String url = "http://127.0.0.1:" + SERVER.getAddress().getPort();
        registry.add("hapbang.clients.lecture.base-url", () -> url);
        registry.add("hapbang.clients.appearance.base-url", () -> url);
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @BeforeEach
    void reset() {
        REQUESTS.clear();
        RESPONSES.clear();
        RESPONSES.put(LECTURE_SUMMARY, ok(summary(ZERO_BUCKET, ZERO_BUCKET, ZERO_BUCKET, ZERO_BUCKET, ZERO_BUCKET,
                ",\"asOf\":\"2026-10-16T12:00:00+09:00\"")));
        RESPONSES.put(APPEARANCE_SUMMARY, ok(summary(ZERO_BUCKET, ZERO_BUCKET, ZERO_BUCKET, ZERO_BUCKET, ZERO_BUCKET,
                "")));
        RESPONSES.put(LECTURE_LIST, ok(EMPTY_PAGE));
        RESPONSES.put(APPEARANCE_LIST, ok(EMPTY_PAGE));
    }

    // ---------------------------------------------------------------------------------------------
    // 요약
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("요약: 두 출처를 상태별로 더한 금액·건수와 전체 합계, Billing 조회 시각 asOf(UTC)를 RsData로 준다. 출처별 소계는 없다")
    void summary() throws Exception {
        RESPONSES.put(LECTURE_SUMMARY, ok(summary(bucket(10_000, 1), bucket(20_000, 2), ZERO_BUCKET,
                bucket(3_000, 1), ZERO_BUCKET, ",\"asOf\":\"2026-10-16T12:00:00+09:00\"")));
        RESPONSES.put(APPEARANCE_SUMMARY, ok(summary(bucket(30_000, 1), ZERO_BUCKET, bucket(20_000, 1), ZERO_BUCKET,
                bucket(7_000, 2), "")));

        mockMvc.perform(get(SUMMARY).with(TestLogin.as(5L, Role.INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.message").value("지급 예정 수익 요약을 조회했습니다."))
                .andExpect(jsonPath("$.data.length()").value(7))
                .andExpect(jsonPath("$.data.confirmationPending.amountHp").value(40_000))
                .andExpect(jsonPath("$.data.confirmationPending.count").value(2))
                .andExpect(jsonPath("$.data.scheduled.amountHp").value(20_000))
                .andExpect(jsonPath("$.data.scheduled.count").value(2))
                .andExpect(jsonPath("$.data.onHold.amountHp").value(20_000))
                .andExpect(jsonPath("$.data.processing.amountHp").value(3_000))
                .andExpect(jsonPath("$.data.failed.amountHp").value(7_000))
                .andExpect(jsonPath("$.data.failed.count").value(2))
                .andExpect(jsonPath("$.data.total.amountHp").value(90_000))
                .andExpect(jsonPath("$.data.total.count").value(8))
                .andExpect(jsonPath("$.data.asOf").value(org.hamcrest.Matchers.endsWith("Z")))
                .andExpect(jsonPath("$.data.lecture").doesNotExist())
                .andExpect(jsonPath("$.data.appearance").doesNotExist());

        assertThat(REQUESTS).containsExactly(
                LECTURE_SUMMARY + "?userId=5 key=" + IntegrationTest.INTERNAL_API_KEY,
                APPEARANCE_SUMMARY + "?userId=5 key=" + IntegrationTest.INTERNAL_API_KEY);
    }

    @Test
    @DisplayName("수익이 없는 일반 유저도 조회할 수 있고 모든 상태가 0원·0건이다")
    void summary_userZero() throws Exception {
        mockMvc.perform(get(SUMMARY).with(TestLogin.as(5L, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.confirmationPending.amountHp").value(0))
                .andExpect(jsonPath("$.data.scheduled.count").value(0))
                .andExpect(jsonPath("$.data.onHold.amountHp").value(0))
                .andExpect(jsonPath("$.data.processing.amountHp").value(0))
                .andExpect(jsonPath("$.data.failed.amountHp").value(0))
                .andExpect(jsonPath("$.data.total.amountHp").value(0))
                .andExpect(jsonPath("$.data.total.count").value(0));
    }

    @Test
    @DisplayName("요약: 한쪽 출처가 5xx·4xx면 부분 합계 없이 503 ProblemDetail이고 실패한 출처를 source로 준다")
    void summary_sourceFailure() throws Exception {
        RESPONSES.put(APPEARANCE_SUMMARY, new Response(500, null));
        mockMvc.perform(get(SUMMARY).with(TestLogin.as(5L, Role.USER)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.code").value("EARNING_SOURCE_UNAVAILABLE"))
                .andExpect(jsonPath("$.source").value("APPEARANCE"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(IntegrationTest.INTERNAL_API_KEY))));

        RESPONSES.put(APPEARANCE_SUMMARY, RESPONSES.get(LECTURE_SUMMARY));
        RESPONSES.put(LECTURE_SUMMARY, new Response(401, null));
        mockMvc.perform(get(SUMMARY).with(TestLogin.as(5L, Role.USER)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("EARNING_SOURCE_REJECTED"))
                .andExpect(jsonPath("$.source").value("LECTURE"));
    }

    // ---------------------------------------------------------------------------------------------
    // 목록
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("목록: 두 출처를 전역 순서로 합치고 시각은 UTC Instant, null 지급 예정 시각은 null, 전체 건수는 두 출처의 합이다")
    void list() throws Exception {
        RESPONSES.put(LECTURE_LIST, ok(page("""
                {"sourceType":"LECTURE","sourceId":31,"earningType":"LECTURE_SALE","title":"강의 A","amountHp":10000,
                 "status":"SCHEDULED","payoutScheduledAt":"2026-11-30T09:00:00+09:00",
                 "statusUpdatedAt":"2026-10-31T09:00:00+09:00"},
                {"sourceType":"LECTURE","sourceId":30,"earningType":"LECTURE_SALE","title":"강의 B","amountHp":5000,
                 "status":"ON_HOLD","statusUpdatedAt":"2026-10-29T09:00:00+09:00"}""", 2)));
        RESPONSES.put(APPEARANCE_LIST, ok(page("""
                {"sourceType":"APPEARANCE","sourceId":12,"earningType":"APPEARANCE_FEE","title":"합방","amountHp":30000,
                 "status":"CONFIRMATION_PENDING","statusUpdatedAt":"2026-10-30T09:00:00"}""", 1)));

        mockMvc.perform(get(LIST).with(TestLogin.as(5L, Role.VERIFIED_INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.message").value("지급 예정 수익 목록을 조회했습니다."))
                .andExpect(jsonPath("$.data.page.size").value(20))
                .andExpect(jsonPath("$.data.page.number").value(0))
                .andExpect(jsonPath("$.data.page.totalElements").value(3))
                .andExpect(jsonPath("$.data.page.totalPages").value(1))
                .andExpect(jsonPath("$.data.content.length()").value(3))
                .andExpect(jsonPath("$.data.content[0].sourceType").value("LECTURE"))
                .andExpect(jsonPath("$.data.content[0].sourceId").value(31))
                .andExpect(jsonPath("$.data.content[0].earningType").value("LECTURE_SALE"))
                .andExpect(jsonPath("$.data.content[0].title").value("강의 A"))
                .andExpect(jsonPath("$.data.content[0].amountHp").value(10000))
                .andExpect(jsonPath("$.data.content[0].status").value("SCHEDULED"))
                .andExpect(jsonPath("$.data.content[0].payoutScheduledAt").value("2026-11-30T00:00:00Z"))
                .andExpect(jsonPath("$.data.content[0].statusUpdatedAt").value("2026-10-31T00:00:00Z"))
                .andExpect(jsonPath("$.data.content[1].sourceType").value("APPEARANCE"))
                .andExpect(jsonPath("$.data.content[1].earningType").value("APPEARANCE_FEE"))
                .andExpect(jsonPath("$.data.content[1].statusUpdatedAt").value("2026-10-30T00:00:00Z"))
                .andExpect(jsonPath("$.data.content[1].payoutScheduledAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.content[2].sourceId").value(30));

        // 상태를 주지 않으면 미지급 다섯 상태로, 출처마다 0페이지 20건을 로그인 회원 ID로 부른다
        assertThat(REQUESTS).hasSize(2).allSatisfy(request -> assertThat(request)
                .contains("userId=5&", "status=CONFIRMATION_PENDING", "status=SCHEDULED", "status=ON_HOLD",
                        "status=PROCESSING", "status=FAILED", "page=0&size=20")
                .doesNotContain("COMPLETED", "CANCELED"));
    }

    @Test
    @DisplayName("목록: 명시한 status만 넘기고, 빈 status는 미지급 상태이며, 요청의 userId는 무시한다")
    void list_statusAndOwner() throws Exception {
        mockMvc.perform(get(LIST).param("status", "COMPLETED").param("status", "CANCELED").param("userId", "99")
                        .with(TestLogin.as(5L, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(0))
                .andExpect(jsonPath("$.data.page.totalElements").value(0));
        assertThat(REQUESTS).hasSize(2).allSatisfy(request -> assertThat(request)
                .contains("userId=5&", "status=COMPLETED", "status=CANCELED")
                .doesNotContain("userId=99", "status=FAILED"));

        REQUESTS.clear();
        mockMvc.perform(get(LIST).param("status", "").with(TestLogin.as(5L, Role.USER)))
                .andExpect(status().isOk());
        assertThat(REQUESTS).hasSize(2).allSatisfy(request -> assertThat(request)
                .contains("status=CONFIRMATION_PENDING", "status=FAILED").doesNotContain("COMPLETED"));
    }

    @ParameterizedTest
    @CsvSource({"page, -1", "size, 0", "size, 101", "page, x", "size, 1.5", "status, PAID", "status, unpaid"})
    @DisplayName("목록: page < 0, size 범위 밖, 숫자가 아닌 값, 없는 status는 출처를 부르지 않고 400 INVALID_REQUEST다")
    void list_invalid(String name, String value) throws Exception {
        mockMvc.perform(get(LIST).param(name, value).with(TestLogin.as(5L, Role.USER)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value(name));
        assertThat(REQUESTS).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"10, 100", "50, 20", "2147483647, 100"})
    @DisplayName("목록: (page + 1) × size > 1,000이면 400 EARNING_PAGE_TOO_DEEP이고, 정확히 1,000은 허용한다")
    void list_tooDeep(String page, String size) throws Exception {
        mockMvc.perform(get(LIST).param("page", page).param("size", size).with(TestLogin.as(5L, Role.USER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EARNING_PAGE_TOO_DEEP"));
        assertThat(REQUESTS).isEmpty();

        mockMvc.perform(get(LIST).param("page", "9").param("size", "100").with(TestLogin.as(5L, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page.number").value(9))
                .andExpect(jsonPath("$.data.content.length()").value(0));
    }

    @Test
    @DisplayName("목록: 한쪽 출처 응답을 읽을 수 없으면 부분 목록 없이 503이다")
    void list_sourceFailure() throws Exception {
        RESPONSES.put(LECTURE_LIST, ok(page("""
                {"sourceType":"LECTURE","sourceId":31,"earningType":"LECTURE_SALE","title":"강의 A","amountHp":10000,
                 "status":"SCHEDULED","statusUpdatedAt":"2026-10-31T09:00:00+09:00"}""", 1)));
        RESPONSES.put(APPEARANCE_LIST, ok("{\"content\":["));

        mockMvc.perform(get(LIST).with(TestLogin.as(5L, Role.USER)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("EARNING_SOURCE_UNAVAILABLE"))
                .andExpect(jsonPath("$.source").value("APPEARANCE"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.content").doesNotExist());
    }

    // ---------------------------------------------------------------------------------------------
    // 인증·인가
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"USER", "INFLUENCER", "VERIFIED_INFLUENCER"})
    @DisplayName("유저·인플루언서·인증된 인플루언서는 요약·목록을 조회할 수 있다")
    void roles(Role role) throws Exception {
        mockMvc.perform(get(SUMMARY).with(TestLogin.as(5L, role))).andExpect(status().isOk());
        mockMvc.perform(get(LIST).with(TestLogin.as(5L, role))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("비로그인 401, 관리자 403(ProblemDetail)이고 출처를 부르지 않는다")
    void unauthorized() throws Exception {
        for (String url : List.of(SUMMARY, LIST)) {
            mockMvc.perform(get(url))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.data").doesNotExist());
            mockMvc.perform(get(url).with(TestLogin.as(1L, Role.ADMIN)))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        assertThat(REQUESTS).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------

    private record Response(int status, String body) {
    }

    private static Response ok(String data) {
        return new Response(200, "{\"statusCode\":200,\"message\":\"ok\",\"data\":" + data + "}");
    }

    private static String bucket(long amountHp, long count) {
        return "{\"amountHp\":" + amountHp + ",\"count\":" + count + "}";
    }

    private static String summary(String cp, String scheduled, String onHold, String processing, String failed,
            String extra) {
        return "{\"confirmationPending\":" + cp + ",\"scheduled\":" + scheduled + ",\"onHold\":" + onHold
                + ",\"processing\":" + processing + ",\"failed\":" + failed + extra + "}";
    }

    private static String page(String items, int total) {
        return "{\"content\":[" + items + "],\"page\":{\"size\":20,\"number\":0,\"totalElements\":" + total
                + ",\"totalPages\":1}}";
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                REQUESTS.add(exchange.getRequestURI() + " key="
                        + exchange.getRequestHeaders().getFirst(InternalApiHeaders.API_KEY));
                Response response = RESPONSES.getOrDefault(path, new Response(404, null));
                byte[] bytes = response.body() == null ? new byte[0]
                        : response.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(response.status(), bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    exchange.getResponseBody().write(bytes);
                }
                exchange.close();
            });
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
