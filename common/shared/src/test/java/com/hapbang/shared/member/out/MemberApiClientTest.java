package com.hapbang.shared.member.out;

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

import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.ContentCategoriesDto;
import com.hapbang.shared.member.dto.ContentCategoryDto;
import com.hapbang.shared.member.dto.InfluencerChannelDto;
import com.hapbang.shared.member.dto.InfluencerStatsDto;
import com.hapbang.shared.member.dto.InfluencerStatsPageDto;
import com.hapbang.shared.member.dto.MemberChangePageDto;
import com.hapbang.shared.member.dto.MemberPageDto;
import com.hapbang.shared.member.dto.RatingDto;
import com.hapbang.shared.member.dto.UserDto;
import com.sun.net.httpserver.HttpServer;

// 회원 내부 API Client의 요청 모양·응답 해제·오류 변환을 가짜 HTTP 서버로 확인한다 (#303). 실제 회원 API는 회원 통합 테스트가 부른다.
class MemberApiClientTest {

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
    void 회원_목록은_afterId와_size와_내부_키_헤더를_붙여_보내고_data를_돌려준다() {
        respond(200, "application/json", """
                {"statusCode":200,"message":"회원 목록을 조회했습니다.","data":{"items":[
                 {"id":3,"nickname":"회원3","role":"INFLUENCER","status":"ACTIVE","version":2},
                 {"id":4,"nickname":"회원4","role":"USER","status":"WITHDRAWN","version":0}],
                 "nextAfterId":4}}""");

        MemberPageDto page = client().getMembers(2L, 2);

        assertThat(requests).containsExactly("/api/v1/internal/members?size=2&afterId=2 key=" + API_KEY);
        assertThat(page.items()).containsExactly(
                new UserDto(3L, "회원3", Role.INFLUENCER, UserStatus.ACTIVE, 2L),
                new UserDto(4L, "회원4", Role.USER, UserStatus.WITHDRAWN, 0L));
        assertThat(page.nextAfterId()).isEqualTo(4L);
    }

    @Test
    void afterId가_null이면_처음부터_조회하고_마지막_페이지의_nextAfterId는_null이다() {
        respond(200, "application/json", """
                {"statusCode":200,"message":"","data":{"items":[]}}""");

        MemberPageDto page = client().getMembers(null, 500);

        assertThat(requests).containsExactly("/api/v1/internal/members?size=500 key=" + API_KEY);
        assertThat(page.items()).isEmpty();
        assertThat(page.nextAfterId()).isNull();
    }

    @Test
    void 인플루언서_통계는_afterId와_size를_붙여_보내고_채널_모두와_평점을_돌려준다() {
        respond(200, "application/json", """
                {"statusCode":200,"message":"인플루언서 통계를 조회했습니다.","data":{"items":[
                 {"userId":3,"version":2,"channels":[{"channelName":"본채널","followerCount":1200},
                  {"channelName":"다시보기","followerCount":300}],"appearanceRating":{"average":4.3,"count":7}},
                 {"userId":5,"version":0,"channels":[],"appearanceRating":{"count":0}}],
                 "nextAfterId":5}}""");

        InfluencerStatsPageDto page = client().getInfluencerStats(2L, 2);

        assertThat(requests).containsExactly("/api/v1/internal/members/influencer-stats?size=2&afterId=2 key=" + API_KEY);
        assertThat(page.items()).containsExactly(
                new InfluencerStatsDto(3L, 2L, List.of(new InfluencerChannelDto("본채널", 1200),
                        new InfluencerChannelDto("다시보기", 300)), new RatingDto(4.3, 7)),
                // 리뷰가 없으면 평균이 응답에서 빠진다(null)
                new InfluencerStatsDto(5L, 0L, List.of(), new RatingDto(null, 0)));
        assertThat(page.nextAfterId()).isEqualTo(5L);

        requests.clear();
        respond(200, "application/json", """
                {"statusCode":200,"message":"","data":{"items":[]}}""");
        assertThat(client().getInfluencerStats(null, 500).nextAfterId()).isNull();
        assertThat(requests).containsExactly("/api/v1/internal/members/influencer-stats?size=500 key=" + API_KEY);
    }

    @Test
    void 인플루언서_통계에서_channels나_평점이_빠진_응답은_호출_불가_예외로_바뀐다() {
        respond(200, "application/json", """
                {"statusCode":200,"message":"","data":{"items":[
                 {"userId":3,"version":2,"appearanceRating":{"count":0}}]}}""");
        assertUnavailable(() -> client().getInfluencerStats(null, 100), 200);

        respond(200, "application/json", """
                {"statusCode":200,"message":"","data":{"items":[{"userId":3,"version":2,"channels":[]}]}}""");
        assertUnavailable(() -> client().getInfluencerStats(null, 100), 200);
    }

    @Test
    void 회원_변경분은_커서_둘을_함께_보내고_다음_커서를_돌려준다() {
        respond(200, "application/json", """
                {"statusCode":200,"message":"회원 변경분을 조회했습니다.","data":{"items":[
                 {"id":7,"nickname":"회원7","role":"INFLUENCER","status":"SUSPENDED","version":4,
                  "contentCategories":["GAME"],"bio":"소개"}],
                 "nextAfterUpdatedAt":"2026-11-09T10:15:30.123456","nextAfterId":7}}""");

        MemberChangePageDto page = client().getChanges(LocalDateTime.of(2026, 11, 9, 10, 0), 3L, 100);

        // 초가 0이어도 초까지 보낸다
        assertThat(requests).containsExactly(
                "/api/v1/internal/members/changes?size=100&afterUpdatedAt=2026-11-09T10:00:00&afterId=3 key=" + API_KEY);
        assertThat(page.items()).containsExactly(new UserDto(7L, "회원7", Role.INFLUENCER, UserStatus.SUSPENDED, 4L,
                List.of("GAME"), "소개"));
        assertThat(page.nextAfterUpdatedAt()).isEqualTo(LocalDateTime.of(2026, 11, 9, 10, 15, 30, 123_456_000));
        assertThat(page.nextAfterId()).isEqualTo(7L);
    }

    @Test
    void 회원_변경분은_커서가_없으면_처음부터_조회하고_하나만_넘기면_보내지_않는다() {
        respond(200, "application/json", """
                {"statusCode":200,"message":"","data":{"items":[]}}""");

        MemberChangePageDto page = client().getChanges(null, null, 500);

        assertThat(requests).containsExactly("/api/v1/internal/members/changes?size=500 key=" + API_KEY);
        assertThat(page.nextAfterUpdatedAt()).isNull();
        assertThat(page.nextAfterId()).isNull();

        requests.clear();
        assertThatThrownBy(() -> client().getChanges(LocalDateTime.of(2026, 11, 9, 10, 0), null, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client().getChanges(null, 3L, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(requests).isEmpty();
    }

    @Test
    void 콘텐츠_분야_목록은_꺼진_분야까지_돌려준다() {
        respond(200, "application/json", """
                {"statusCode":200,"message":"콘텐츠 분야 목록을 조회했습니다.","data":{"items":[
                 {"code":"GAME","name":"게임","active":true},
                 {"code":"OLD_GAME","name":"옛 게임","active":false}]}}""");

        ContentCategoriesDto categories = client().getContentCategories();

        assertThat(requests).containsExactly("/api/v1/internal/members/content-categories key=" + API_KEY);
        assertThat(categories.items()).containsExactly(new ContentCategoryDto("GAME", "게임", true),
                new ContentCategoryDto("OLD_GAME", "옛 게임", false));

        respond(200, "application/json", "{\"statusCode\":200,\"message\":\"\",\"data\":{}}");
        assertUnavailable(() -> client().getContentCategories(), 200);
    }

    @Test
    void ProblemDetail_오류는_code를_담은_예외로_바뀐다() {
        respond(400, "application/problem+json", """
                {"status":400,"detail":"요청 값이 올바르지 않습니다.","code":"INVALID_REQUEST",
                 "errors":[{"field":"size","message":"1 이상이어야 합니다"}]}""");

        assertThatThrownBy(() -> client().getMembers(null, 0))
                .isInstanceOfSatisfying(MemberApiCallException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(MemberApiCallException.Reason.REJECTED);
                    assertThat(e.getStatus()).isEqualTo(400);
                    assertThat(e.getCode()).isEqualTo("INVALID_REQUEST");
                });

        // 내부 API 키 오류는 본문이 없다
        respond(401, null, null);
        assertThatThrownBy(() -> client().getMembers(null, 100))
                .isInstanceOfSatisfying(MemberApiCallException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(MemberApiCallException.Reason.REJECTED);
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
        MemberApiClient refused = client("http://127.0.0.1:" + closedPort);
        assertUnavailable(() -> refused.getMembers(null, 100), 0);

        respond(200, "application/json", "{\"statusCode\":200,\"message\":\"\",\"data\":null}");
        delayMillis = 1_000L;
        MemberApiClient shortRead = client("http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofMillis(300));
        assertUnavailable(() -> shortRead.getMembers(null, 100), 0);

        delayMillis = 0;
        respond(503, null, null);
        assertUnavailable(() -> client().getMembers(null, 100), 503);
    }

    @Test
    void 읽을_수_없거나_필수_필드가_빠진_성공_응답은_호출_불가_예외로_바뀐다() {
        respond(200, "application/json", "{\"statusCode\":200,\"data\":{\"items\":[");
        assertUnavailable(() -> client().getMembers(null, 100), 200);

        // 모르는 역할 문자열(계약에 없는 값)
        respond(200, "application/json", """
                {"statusCode":200,"message":"","data":{"items":[
                 {"id":1,"nickname":"회원1","role":"NEW_ROLE","status":"ACTIVE","version":0}]}}""");
        assertUnavailable(() -> client().getMembers(null, 100), 200);

        // data 또는 items가 없음
        respond(200, "application/json", "{\"statusCode\":200,\"message\":\"\"}");
        assertUnavailable(() -> client().getMembers(null, 100), 200);
        respond(200, "application/json", "{\"statusCode\":200,\"message\":\"\",\"data\":{}}");
        assertUnavailable(() -> client().getMembers(null, 100), 200);

        respond(204, null, null);
        assertUnavailable(() -> client().getMembers(null, 100), 204);
    }

    @Test
    void 서버_오류의_ProblemDetail_code도_예외에_담는다() {
        respond(500, "application/problem+json", "{\"status\":500,\"code\":\"INTERNAL_ERROR\"}");

        assertThatThrownBy(() -> client().getMembers(null, 100))
                .isInstanceOfSatisfying(MemberApiCallException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(MemberApiCallException.Reason.UNAVAILABLE);
                    assertThat(e.getStatus()).isEqualTo(500);
                    assertThat(e.getCode()).isEqualTo("INTERNAL_ERROR");
                });
    }

    private static void assertUnavailable(Runnable call, int status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(MemberApiCallException.class, e -> {
            assertThat(e.getReason()).isEqualTo(MemberApiCallException.Reason.UNAVAILABLE);
            assertThat(e.getStatus()).isEqualTo(status);
        });
    }

    private void respond(int status, String contentType, String body) {
        this.status = status;
        this.contentType = contentType;
        this.body = body;
    }

    private MemberApiClient client() {
        return client("http://127.0.0.1:" + server.getAddress().getPort());
    }

    // 읽기 timeout은 시간 초과를 시험할 때만 짧게 둔다. 모든 요청에 300ms를 두면 CI 러너에서 클래스의 첫 요청(가짜 서버·클라이언트 클래스 로딩)이
    // 넘겨 Read timed out으로 흔들린다(#559, 2026-11-02 AppearanceApiClientTest).
    private static MemberApiClient client(String baseUrl) {
        return client(baseUrl, Duration.ofSeconds(5));
    }

    private static MemberApiClient client(String baseUrl, Duration readTimeout) {
        return new MemberApiClient(RestClient.builder(),
                new MemberClientProperties(baseUrl, Duration.ofMillis(500), readTimeout), API_KEY);
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
