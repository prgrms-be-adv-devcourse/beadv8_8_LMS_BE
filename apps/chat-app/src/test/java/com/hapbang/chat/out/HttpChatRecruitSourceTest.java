package com.hapbang.chat.out;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.client.RestClient;

import com.hapbang.chat.app.ChatRecruitSource.ChatApplicantPage;
import com.hapbang.chat.app.ChatRecruitSource.ChatRecruit;
import com.hapbang.chat.app.ChatRecruitSourceException;
import com.hapbang.chat.app.ChatRecruitSourceException.Reason;
import com.hapbang.chat.out.appearance.ChatAppearanceApiConfig;
import com.hapbang.chat.out.appearance.ChatAppearanceApiProperties;
import com.hapbang.chat.out.appearance.ChatAppearanceInternalApi;
import com.hapbang.shared.internal.InternalApiHeaders;
import com.sun.net.httpserver.HttpServer;

/**
 * 출연 내부 API를 부르는 chat-app 전용 HTTP Interface와 어댑터의 요청 모양(설계 3.4 계약 A1~A3)·응답 해제·오류 변환을 가짜 HTTP
 * 서버로 확인한다. 회원 내부 API 어댑터와 같은 규칙(내부 키 헤더·RsData 해제·거절/호출 불가 구분)에 404를 모집글 없음으로 더한다.
 */
class HttpChatRecruitSourceTest {

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
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " key="
                    + exchange.getRequestHeaders().getFirst(InternalApiHeaders.API_KEY));
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
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void 모집글은_내부_키_헤더를_붙여_GET으로_읽고_data를_돌려준다() {
        respond(200, "application/json", """
                {"statusCode":200,"message":"","data":{"recruitId":7,"hostId":1,"title":"주말 합방 모집",
                 "status":"RECRUITING"}}""");

        ChatRecruit recruit = source().getRecruit(7L);

        assertThat(requests).containsExactly("GET /api/v1/internal/appearances/recruits/7 key=" + API_KEY);
        assertThat(recruit).isEqualTo(new ChatRecruit(7L, 1L, "주말 합방 모집", "RECRUITING"));
        assertThat(recruit.isRecruiting()).isTrue();
        assertThat(new ChatRecruit(7L, 1L, "t", "HOLD_PENDING").isRecruiting()).isFalse();
    }

    @Test
    void 지원자_목록은_afterUserId와_size를_싣고_마지막_페이지의_다음_값은_null이다() {
        respond(200, "application/json", """
                {"statusCode":200,"message":"","data":{"userIds":[3,4],"nextAfterUserId":4}}""");
        ChatApplicantPage first = source().getApplicants(7L, null, 500);
        respond(200, "application/json", """
                {"statusCode":200,"message":"","data":{"userIds":[5]}}""");
        ChatApplicantPage last = source().getApplicants(7L, 4L, 500);

        assertThat(requests).containsExactly(
                "GET /api/v1/internal/appearances/recruits/7/applicants?size=500 key=" + API_KEY,
                "GET /api/v1/internal/appearances/recruits/7/applicants?afterUserId=4&size=500 key=" + API_KEY);
        assertThat(first).isEqualTo(new ChatApplicantPage(List.of(3L, 4L), 4L));
        assertThat(last).isEqualTo(new ChatApplicantPage(List.of(5L), null));
    }

    @Test
    void 지원_이력_확인은_userIds를_반복_쿼리로_보낸다() {
        respond(200, "application/json", """
                {"statusCode":200,"message":"","data":{"appliedUserIds":[2]}}""");

        Set<Long> applied = source().findApplied(7L, List.of(2L, 3L));

        assertThat(requests).containsExactly(
                "GET /api/v1/internal/appearances/recruits/7/applicants/exists?userIds=2&userIds=3 key=" + API_KEY);
        assertThat(applied).containsExactly(2L);
    }

    @Test
    void 없는_모집글_404는_모집글_없음이고_그_밖의_4xx는_거절이다() {
        respond(404, "application/problem+json", "{\"status\":404,\"code\":\"RECRUIT_NOT_FOUND\"}");
        assertFailure(() -> source().getRecruit(7L), Reason.NOT_FOUND, 404, "RECRUIT_NOT_FOUND");

        respond(400, "application/problem+json", "{\"status\":400,\"code\":\"INVALID_REQUEST\"}");
        assertFailure(() -> source().findApplied(7L, List.of(2L)), Reason.REJECTED, 400, "INVALID_REQUEST");

        respond(401, null, null);
        assertFailure(() -> source().getApplicants(7L, null, 500), Reason.REJECTED, 401, null);
    }

    @Test
    void 서버_오류와_연결_실패와_응답_시간_초과는_호출_불가다() throws IOException {
        respond(500, "application/problem+json", "{\"status\":500,\"code\":\"INTERNAL_ERROR\"}");
        assertFailure(() -> source().getRecruit(7L), Reason.UNAVAILABLE, 500, "INTERNAL_ERROR");

        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        assertFailure(() -> source("http://127.0.0.1:" + closedPort).getRecruit(7L), Reason.UNAVAILABLE, 0, null);

        respond(200, "application/json", "{\"statusCode\":200,\"message\":\"\",\"data\":{\"appliedUserIds\":[]}}");
        delayMillis = 1_000L;
        assertFailure(() -> source().findApplied(7L, List.of(2L)), Reason.UNAVAILABLE, 0, null);
    }

    @Test
    void 읽을_수_없거나_필수_값이_빠진_성공_응답은_호출_불가다() {
        respond(200, "application/json", "{\"statusCode\":200,\"data\":{\"recruitId\":");
        assertFailure(() -> source().getRecruit(7L), Reason.UNAVAILABLE, 0, null);

        respond(200, "application/json", """
                {"statusCode":200,"message":"","data":{"recruitId":7,"title":"제목","status":"RECRUITING"}}""");
        assertFailure(() -> source().getRecruit(7L), Reason.UNAVAILABLE, 0, null);

        respond(200, "application/json", "{\"statusCode\":200,\"message\":\"\",\"data\":{}}");
        assertFailure(() -> source().getApplicants(7L, null, 500), Reason.UNAVAILABLE, 0, null);

        respond(200, "application/json", "{\"statusCode\":200,\"message\":\"\"}");
        assertFailure(() -> source().findApplied(7L, List.of(2L)), Reason.UNAVAILABLE, 200, null);
    }

    @Test
    void 주소가_없으면_호출_불가다() {
        HttpChatRecruitSource notConfigured = new HttpChatRecruitSource(
                new StaticListableBeanFactory().getBeanProvider(ChatAppearanceInternalApi.class));

        assertFailure(() -> notConfigured.getRecruit(7L), Reason.UNAVAILABLE, 0, null);
        assertThat(requests).isEmpty();
    }

    @Test
    void 설정은_회원_내부_API와_같은_기본_제한_시간을_쓰고_주소가_비어_있으면_받지_않는다() {
        ChatAppearanceApiProperties defaults = new ChatAppearanceApiProperties("http://appearance", null, null);

        assertThat(defaults.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(defaults.readTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThatIllegalArgumentException().isThrownBy(() -> new ChatAppearanceApiProperties(" ", null, null));
    }

    private static void assertFailure(Runnable call, Reason reason, int status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ChatRecruitSourceException.class, e -> {
            assertThat(e.getReason()).isEqualTo(reason);
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getCode()).isEqualTo(code);
        });
    }

    private void respond(int status, String contentType, String body) {
        this.status = status;
        this.contentType = contentType;
        this.body = body;
    }

    private HttpChatRecruitSource source() {
        return source("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static HttpChatRecruitSource source(String baseUrl) {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("chatAppearanceInternalApi", ChatAppearanceApiConfig.create(RestClient.builder(),
                new ChatAppearanceApiProperties(baseUrl, Duration.ofMillis(500), Duration.ofMillis(300)), API_KEY));
        return new HttpChatRecruitSource(beanFactory.getBeanProvider(ChatAppearanceInternalApi.class));
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
