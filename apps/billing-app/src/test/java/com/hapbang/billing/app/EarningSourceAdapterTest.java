package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.web.client.RestClient;

import com.hapbang.billing.app.EarningSummaryResult.Amount;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.EarningSourceType;
import com.hapbang.billing.domain.EarningStatus;
import com.hapbang.billing.domain.EarningType;
import com.hapbang.shared.appearance.out.AppearanceApiClient;
import com.hapbang.shared.appearance.out.AppearanceClientProperties;
import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.shared.lecture.out.LectureApiClient;
import com.hapbang.shared.lecture.out.LectureClientProperties;
import com.sun.net.httpserver.HttpServer;

// 강의·출연 수익 출처(#295)가 실제 shared Client로 가짜 내부 API를 불러 Billing 값으로 바꾸는지 확인한다:
// 시각 정규화(강의 offset → Instant, 출연 KST LocalDateTime → Instant, JVM 시간대 무관), null 지급 예정 시각, 요청 모양,
// 장애(5xx·시간 초과·연결 실패·읽을 수 없는 응답 → UNAVAILABLE, 4xx → REJECTED), 계약에 맞지 않는 응답 거절, Client 없음.
class EarningSourceAdapterTest {

    private static final String API_KEY = "billing-unit-internal-key";
    private static final Set<EarningStatus> UNPAID = EarningStatus.UNPAID;

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile int status;
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
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    // ---------------------------------------------------------------------------------------------
    // 강의
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("강의 요약을 상태별 금액·건수로 바꾸고 userId·내부 키를 Client가 붙인다")
    void lecture_summary() {
        respond(200, rs("""
                {"confirmationPending":{"amountHp":10000,"count":1},"scheduled":{"amountHp":20000,"count":2},
                 "onHold":{"amountHp":0,"count":0},"processing":{"amountHp":3000,"count":1},
                 "failed":{"amountHp":5000,"count":1},"asOf":"2026-11-05T12:00:00+09:00"}"""));

        EarningSummaryResult.Buckets buckets = lecture().summary(7L);

        assertThat(buckets).isEqualTo(new EarningSummaryResult.Buckets(new Amount(10_000, 1), new Amount(20_000, 2),
                new Amount(0, 0), new Amount(3_000, 1), new Amount(5_000, 1)));
        assertThat(requests).containsExactly("/api/v1/internal/lectures/earnings/summary?userId=7 key=" + API_KEY);
    }

    @Test
    @DisplayName("강의 목록: +09:00·UTC offset 시각을 같은 Instant로 바꾸고, null 지급 예정 시각은 null로 둔다")
    void lecture_items() {
        respond(200, rs("""
                {"content":[
                  {"sourceType":"LECTURE","sourceId":31,"earningType":"LECTURE_SALE","title":"강의 A","amountHp":10000,
                   "status":"SCHEDULED","payoutScheduledAt":"2026-11-30T09:00:00+09:00",
                   "statusUpdatedAt":"2026-10-31T09:00:00.123456+09:00"},
                  {"sourceType":"LECTURE","sourceId":30,"earningType":"LECTURE_SALE","title":"강의 B","amountHp":5000,
                   "status":"ON_HOLD","statusUpdatedAt":"2026-10-30T15:00:00Z"}],
                 "page":{"size":20,"number":0,"totalElements":2,"totalPages":1}}"""));

        EarningSource.SourcePage page = lecture().earnings(7L, EnumSet.of(EarningStatus.SCHEDULED,
                EarningStatus.ON_HOLD), 0, 20);

        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.content()).containsExactly(
                new EarningItemResult(EarningSourceType.LECTURE, 31L, EarningType.LECTURE_SALE, "강의 A", 10_000L,
                        EarningStatus.SCHEDULED, Instant.parse("2026-11-30T00:00:00Z"),
                        Instant.parse("2026-10-31T00:00:00.123456Z")),
                new EarningItemResult(EarningSourceType.LECTURE, 30L, EarningType.LECTURE_SALE, "강의 B", 5_000L,
                        EarningStatus.ON_HOLD, null, Instant.parse("2026-10-30T15:00:00Z")));
        assertThat(requests).singleElement().asString()
                .startsWith("/api/v1/internal/lectures/earnings?userId=7&status=")
                .contains("status=SCHEDULED", "status=ON_HOLD", "page=0&size=20");
    }

    // ---------------------------------------------------------------------------------------------
    // 출연
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("출연 목록: offset 없는 시각을 JVM 기본 시간대가 아니라 Asia/Seoul로 해석해 Instant로 바꾼다")
    void appearance_items_kst() {
        respond(200, rs("""
                {"content":[
                  {"sourceType":"APPEARANCE","sourceId":12,"earningType":"APPEARANCE_PENALTY","title":"합방 요청",
                   "amountHp":6000,"status":"PROCESSING","statusUpdatedAt":"2026-10-31T09:00:00.654321"}],
                 "page":{"size":20,"number":0,"totalElements":1,"totalPages":1}}"""));
        TimeZone original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
        try {
            EarningSource.SourcePage page = appearance().earnings(8L, UNPAID, 0, 20);

            assertThat(page.content()).containsExactly(new EarningItemResult(EarningSourceType.APPEARANCE, 12L,
                    EarningType.APPEARANCE_PENALTY, "합방 요청", 6_000L, EarningStatus.PROCESSING, null,
                    Instant.parse("2026-10-31T00:00:00.654321Z")));
        } finally {
            TimeZone.setDefault(original);
        }
        assertThat(requests).singleElement().asString()
                .startsWith("/api/v1/internal/appearances/earnings?userId=8&status=")
                .endsWith("page=0&size=20 key=" + API_KEY);
    }

    @Test
    @DisplayName("출연 요약(asOf 없음)을 상태별 금액·건수로 바꾼다")
    void appearance_summary() {
        respond(200, rs("""
                {"confirmationPending":{"amountHp":30000,"count":1},"scheduled":{"amountHp":0,"count":0},
                 "onHold":{"amountHp":20000,"count":1},"processing":{"amountHp":0,"count":0},
                 "failed":{"amountHp":7000,"count":2}}"""));

        assertThat(appearance().summary(8L)).isEqualTo(new EarningSummaryResult.Buckets(new Amount(30_000, 1),
                new Amount(0, 0), new Amount(20_000, 1), new Amount(0, 0), new Amount(7_000, 2)));
    }

    // ---------------------------------------------------------------------------------------------
    // 장애
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("5xx·응답 시간 초과·읽을 수 없는 본문·data 없음은 EARNING_SOURCE_UNAVAILABLE이고 실패한 출처를 담는다")
    void unavailable() {
        respond(503, null);
        assertFailure(() -> lecture().summary(7L), EarningSourceType.LECTURE,
                BillingErrorCode.EARNING_SOURCE_UNAVAILABLE);
        assertFailure(() -> appearance().summary(7L), EarningSourceType.APPEARANCE,
                BillingErrorCode.EARNING_SOURCE_UNAVAILABLE);

        respond(200, "{\"statusCode\":200,\"data\":{\"content\":[");
        assertFailure(() -> lecture().earnings(7L, UNPAID, 0, 20), EarningSourceType.LECTURE,
                BillingErrorCode.EARNING_SOURCE_UNAVAILABLE);
        assertFailure(() -> appearance().earnings(7L, UNPAID, 0, 20), EarningSourceType.APPEARANCE,
                BillingErrorCode.EARNING_SOURCE_UNAVAILABLE);

        respond(200, "{\"statusCode\":200,\"message\":\"\",\"data\":null}");
        assertFailure(() -> lecture().summary(7L), EarningSourceType.LECTURE,
                BillingErrorCode.EARNING_SOURCE_UNAVAILABLE);

        respond(200, rs("{\"content\":[],\"page\":{\"size\":20,\"number\":0,\"totalElements\":0,\"totalPages\":0}}"));
        delayMillis = 1_000L;
        assertFailure(() -> new LectureEarningSource(() -> lectureClient(Duration.ofMillis(200)))
                .earnings(7L, UNPAID, 0, 20), EarningSourceType.LECTURE, BillingErrorCode.EARNING_SOURCE_UNAVAILABLE);
        assertFailure(() -> new AppearanceEarningSource(() -> appearanceClient(Duration.ofMillis(200)))
                .earnings(7L, UNPAID, 0, 20), EarningSourceType.APPEARANCE,
                BillingErrorCode.EARNING_SOURCE_UNAVAILABLE);
    }

    @Test
    @DisplayName("연결할 수 없으면 EARNING_SOURCE_UNAVAILABLE이다")
    void connectionRefused() {
        String url = baseUrl();
        server.stop(0);

        assertFailure(() -> new LectureEarningSource(() -> new LectureApiClient(RestClient.builder(),
                        new LectureClientProperties(url, Duration.ofSeconds(1), Duration.ofSeconds(1)), API_KEY))
                .summary(7L), EarningSourceType.LECTURE, BillingErrorCode.EARNING_SOURCE_UNAVAILABLE);
    }

    @Test
    @DisplayName("4xx(내부 키 오류·잘못된 요청)는 일시 장애가 아니라 EARNING_SOURCE_REJECTED(503)다")
    void rejected() {
        respond(401, null);
        assertFailure(() -> lecture().summary(7L), EarningSourceType.LECTURE,
                BillingErrorCode.EARNING_SOURCE_REJECTED);
        respond(400, "{\"status\":400,\"code\":\"INVALID_REQUEST\",\"detail\":\"x\"}");
        assertFailure(() -> appearance().earnings(7L, UNPAID, 0, 20), EarningSourceType.APPEARANCE,
                BillingErrorCode.EARNING_SOURCE_REJECTED);
    }

    @Test
    @DisplayName("계약에 맞지 않는 응답(다른 출처 이름·요청하지 않은 상태·음수 금액·페이지 크기 초과)은 부분 결과 없이 UNAVAILABLE이다")
    void invalidResponse() {
        respond(200, page("""
                {"sourceType":"APPEARANCE","sourceId":1,"earningType":"LECTURE_SALE","title":"t","amountHp":1,
                 "status":"SCHEDULED","statusUpdatedAt":"2026-10-31T09:00:00+09:00"}""", 1));
        assertFailure(() -> lecture().earnings(7L, UNPAID, 0, 20), EarningSourceType.LECTURE,
                BillingErrorCode.EARNING_SOURCE_UNAVAILABLE);

        respond(200, page("""
                {"sourceType":"LECTURE","sourceId":1,"earningType":"LECTURE_SALE","title":"t","amountHp":1,
                 "status":"COMPLETED","statusUpdatedAt":"2026-10-31T09:00:00+09:00"}""", 1));
        assertFailure(() -> lecture().earnings(7L, UNPAID, 0, 20), EarningSourceType.LECTURE,
                BillingErrorCode.EARNING_SOURCE_UNAVAILABLE);

        respond(200, page("""
                {"sourceType":"APPEARANCE","sourceId":1,"earningType":"APPEARANCE_FEE","title":"t","amountHp":-1,
                 "status":"FAILED","statusUpdatedAt":"2026-10-31T09:00:00"}""", 1));
        assertFailure(() -> appearance().earnings(7L, UNPAID, 0, 20), EarningSourceType.APPEARANCE,
                BillingErrorCode.EARNING_SOURCE_UNAVAILABLE);

        String item = """
                {"sourceType":"APPEARANCE","sourceId":1,"earningType":"APPEARANCE_FEE","title":"t","amountHp":1,
                 "status":"FAILED","statusUpdatedAt":"2026-10-31T09:00:00"}""";
        respond(200, page(item + "," + item, 2));
        assertFailure(() -> appearance().earnings(7L, UNPAID, 0, 1), EarningSourceType.APPEARANCE,
                BillingErrorCode.EARNING_SOURCE_UNAVAILABLE);

        respond(200, rs("""
                {"confirmationPending":{"amountHp":-1,"count":1},"scheduled":{"amountHp":0,"count":0},
                 "onHold":{"amountHp":0,"count":0},"processing":{"amountHp":0,"count":0},
                 "failed":{"amountHp":0,"count":0}}"""));
        assertFailure(() -> appearance().summary(7L), EarningSourceType.APPEARANCE,
                BillingErrorCode.EARNING_SOURCE_UNAVAILABLE);
    }

    @Test
    @DisplayName("Client 빈이 없으면(내부 API 주소 미설정) 호출 없이 EARNING_SOURCE_UNAVAILABLE이다")
    void noClient() {
        assertFailure(() -> new LectureEarningSource(() -> null).summary(7L), EarningSourceType.LECTURE,
                BillingErrorCode.EARNING_SOURCE_UNAVAILABLE);
        assertFailure(() -> new AppearanceEarningSource(() -> null).earnings(7L, UNPAID, 0, 20),
                EarningSourceType.APPEARANCE, BillingErrorCode.EARNING_SOURCE_UNAVAILABLE);
        assertThat(requests).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------

    private void assertFailure(Executable call, EarningSourceType source, BillingErrorCode code) {
        assertThatThrownBy(call::execute).isInstanceOfSatisfying(EarningSourceException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(code);
            assertThat(e.getSource()).isEqualTo(source);
            assertThat(e.getProperties()).containsEntry("source", source.name());
            assertThat(e.getMessage()).doesNotContain(API_KEY);
        });
    }

    private LectureEarningSource lecture() {
        return new LectureEarningSource(() -> lectureClient(Duration.ofSeconds(5)));
    }

    private AppearanceEarningSource appearance() {
        return new AppearanceEarningSource(() -> appearanceClient(Duration.ofSeconds(5)));
    }

    private LectureApiClient lectureClient(Duration readTimeout) {
        return new LectureApiClient(RestClient.builder(),
                new LectureClientProperties(baseUrl(), Duration.ofSeconds(2), readTimeout), API_KEY);
    }

    private AppearanceApiClient appearanceClient(Duration readTimeout) {
        return new AppearanceApiClient(RestClient.builder(),
                new AppearanceClientProperties(baseUrl(), Duration.ofSeconds(2), readTimeout), API_KEY);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void respond(int status, String body) {
        this.status = status;
        this.body = body;
        this.delayMillis = 0;
    }

    private static String rs(String data) {
        return "{\"statusCode\":200,\"message\":\"ok\",\"data\":" + data + "}";
    }

    private static String page(String items, int total) {
        return rs("{\"content\":[" + items + "],\"page\":{\"size\":20,\"number\":0,\"totalElements\":" + total
                + ",\"totalPages\":1}}");
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
