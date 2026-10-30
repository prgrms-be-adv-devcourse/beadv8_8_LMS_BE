package com.hapbang.chat.out;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.client.RestClient;

import com.hapbang.chat.ChatApplication;
import com.hapbang.chat.app.ChatMemberSourceException;
import com.hapbang.chat.app.ChatMemberSourceException.Reason;
import com.hapbang.chat.app.ChatUserBackfillProperties;
import com.hapbang.chat.app.ChatUserSyncService;
import com.hapbang.chat.app.DefaultChatUserBackfillService;
import com.hapbang.chat.app.exception.ChatErrorCode;
import com.hapbang.chat.app.exception.ChatUserBackfillException;
import com.hapbang.chat.out.member.ChatMemberApiConfig;
import com.hapbang.chat.out.member.ChatMemberApiProperties;
import com.hapbang.chat.out.member.ChatMemberInternalApi;
import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.MemberPageDto;
import com.hapbang.shared.member.dto.UserDto;
import com.sun.net.httpserver.HttpServer;

/**
 * 회원 내부 API를 부르는 chat-app 전용 HTTP Interface와 어댑터의 요청 모양·응답 해제·오류 변환을 가짜 HTTP 서버로 확인한다.
 * shared 공통 Client의 동작(같은 경로·쿼리·내부 키 헤더·RsData 해제·거절/호출 불가 구분)을 그대로 지키는지 본다.
 */
class HttpChatMemberSnapshotSourceTest {

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
        // 시간 초과를 일으킨 지연 응답이 다음 요청을 막지 않게 요청마다 스레드를 쓴다
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void 회원_목록은_size와_afterId와_내부_키_헤더를_붙여_GET으로_보내고_data를_돌려준다() {
        respond(200, "application/json", """
                {"statusCode":200,"message":"회원 목록을 조회했습니다.","data":{"items":[
                 {"id":3,"nickname":"회원3","role":"INFLUENCER","status":"ACTIVE","version":2},
                 {"id":4,"nickname":"회원4","role":"USER","status":"WITHDRAWN","version":0}],
                 "nextAfterId":4}}""");

        MemberPageDto page = source().readPage(2L, 2);

        assertThat(requests).containsExactly("GET /api/v1/internal/members?size=2&afterId=2 key=" + API_KEY);
        assertThat(page.items()).containsExactly(
                new UserDto(3L, "회원3", Role.INFLUENCER, UserStatus.ACTIVE, 2L),
                new UserDto(4L, "회원4", Role.USER, UserStatus.WITHDRAWN, 0L));
        assertThat(page.nextAfterId()).isEqualTo(4L);
    }

    @Test
    void afterId가_null이면_쿼리에서_빼고_마지막_페이지의_nextAfterId는_null이다() {
        respond(200, "application/json", """
                {"statusCode":200,"message":"","data":{"items":[]}}""");

        MemberPageDto page = HttpChatMemberSnapshotSource.read(api(), null, 500);

        assertThat(requests).containsExactly("GET /api/v1/internal/members?size=500 key=" + API_KEY);
        assertThat(page.items()).isEmpty();
        assertThat(page.nextAfterId()).isNull();
    }

    @Test
    void 클라이언트_오류_4xx는_상태와_ProblemDetail_code를_담은_거절이다() {
        respond(400, "application/problem+json", """
                {"status":400,"detail":"요청 값이 올바르지 않습니다.","code":"INVALID_REQUEST",
                 "errors":[{"field":"size","message":"1 이상이어야 합니다"}]}""");

        assertFailure(() -> source().readPage(0, 0), Reason.REJECTED, 400, "INVALID_REQUEST");
    }

    @Test
    void 본문_없는_401도_거절이고_code는_없다() {
        respond(401, null, null);

        assertFailure(() -> source().readPage(0, 100), Reason.REJECTED, 401, null);
    }

    @Test
    void 서버_오류_5xx는_호출_불가이고_ProblemDetail_code를_담는다() {
        respond(500, "application/problem+json", "{\"status\":500,\"code\":\"INTERNAL_ERROR\"}");
        assertFailure(() -> source().readPage(0, 100), Reason.UNAVAILABLE, 500, "INTERNAL_ERROR");

        respond(503, null, null);
        assertFailure(() -> source().readPage(0, 100), Reason.UNAVAILABLE, 503, null);
    }

    @Test
    void 연결_실패와_응답_시간_초과는_호출_불가다() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        HttpChatMemberSnapshotSource refused = source("http://127.0.0.1:" + closedPort);
        assertFailure(() -> refused.readPage(0, 100), Reason.UNAVAILABLE, 0, null);

        respond(200, "application/json", "{\"statusCode\":200,\"message\":\"\",\"data\":{\"items\":[]}}");
        delayMillis = 1_000L;
        assertFailure(() -> source().readPage(0, 100), Reason.UNAVAILABLE, 0, null);
    }

    @Test
    void 읽을_수_없거나_필수_필드가_빠진_성공_응답은_호출_불가다() {
        respond(200, "application/json", "{\"statusCode\":200,\"data\":{\"items\":[");
        assertFailure(() -> source().readPage(0, 100), Reason.UNAVAILABLE, 0, null);

        // 모르는 역할 문자열(계약에 없는 값)
        respond(200, "application/json", """
                {"statusCode":200,"message":"","data":{"items":[
                 {"id":1,"nickname":"회원1","role":"NEW_ROLE","status":"ACTIVE","version":0}]}}""");
        assertFailure(() -> source().readPage(0, 100), Reason.UNAVAILABLE, 0, null);

        // items가 없음
        respond(200, "application/json", "{\"statusCode\":200,\"message\":\"\",\"data\":{}}");
        assertFailure(() -> source().readPage(0, 100), Reason.UNAVAILABLE, 0, null);

        // data가 없음
        respond(200, "application/json", "{\"statusCode\":200,\"message\":\"\"}");
        assertFailure(() -> source().readPage(0, 100), Reason.UNAVAILABLE, 200, null);
        respond(200, "application/json", "{\"statusCode\":200,\"message\":\"\",\"data\":null}");
        assertFailure(() -> source().readPage(0, 100), Reason.UNAVAILABLE, 200, null);

        // 본문 없음
        respond(204, null, null);
        assertFailure(() -> source().readPage(0, 100), Reason.UNAVAILABLE, 204, null);
    }

    @Test
    void 주소가_없으면_설정되지_않은_것이고_백필은_회원_원본_호출_불가로_멈춘다() {
        HttpChatMemberSnapshotSource notConfigured = new HttpChatMemberSnapshotSource(
                new StaticListableBeanFactory().getBeanProvider(ChatMemberInternalApi.class));

        assertThat(notConfigured.isConfigured()).isFalse();
        assertThatIllegalStateException().isThrownBy(() -> notConfigured.readPage(0, 100));
        DefaultChatUserBackfillService backfill = new DefaultChatUserBackfillService(notConfigured,
                mock(ChatUserSyncService.class), new ChatUserBackfillProperties(null, null, null));
        assertThatThrownBy(() -> backfill.backfill(0, 10))
                .isInstanceOfSatisfying(ChatUserBackfillException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ChatErrorCode.MEMBER_SOURCE_UNAVAILABLE));
        assertThat(requests).isEmpty();
    }

    @Test
    void 설정은_공통_Client와_같은_키와_기본값을_쓰고_주소가_비어_있으면_받지_않는다() {
        ChatMemberApiProperties defaults = new ChatMemberApiProperties("http://member", null, null);

        assertThat(defaults.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(defaults.readTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThatIllegalArgumentException().isThrownBy(() -> new ChatMemberApiProperties(" ", null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new ChatMemberApiProperties(null, null, null));
    }

    @Test
    void chat_app은_shared의_공통_HTTP_Client를_스캔하지_않는다() {
        SpringBootApplication application = ChatApplication.class.getAnnotation(SpringBootApplication.class);

        assertThat(Arrays.asList(application.scanBasePackages())).containsExactly("com.hapbang.chat");
    }

    private static void assertFailure(Runnable call, Reason reason, int status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ChatMemberSourceException.class, e -> {
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

    private ChatMemberInternalApi api() {
        return api("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static ChatMemberInternalApi api(String baseUrl) {
        return ChatMemberApiConfig.create(RestClient.builder(),
                new ChatMemberApiProperties(baseUrl, Duration.ofMillis(500), Duration.ofMillis(300)), API_KEY);
    }

    private HttpChatMemberSnapshotSource source() {
        return source("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static HttpChatMemberSnapshotSource source(String baseUrl) {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("chatMemberInternalApi", api(baseUrl));
        return new HttpChatMemberSnapshotSource(beanFactory.getBeanProvider(ChatMemberInternalApi.class));
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
