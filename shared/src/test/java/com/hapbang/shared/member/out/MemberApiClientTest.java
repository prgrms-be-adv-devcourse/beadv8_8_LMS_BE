package com.hapbang.shared.member.out;

import static org.assertj.core.api.Assertions.*;

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
import org.springframework.web.client.RestClient;

import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.MemberPageDto;
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
        assertUnavailable(() -> client().getMembers(null, 100), 0);

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

    private static MemberApiClient client(String baseUrl) {
        return new MemberApiClient(RestClient.builder(),
                new MemberClientProperties(baseUrl, Duration.ofMillis(500), Duration.ofMillis(300)), API_KEY);
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
