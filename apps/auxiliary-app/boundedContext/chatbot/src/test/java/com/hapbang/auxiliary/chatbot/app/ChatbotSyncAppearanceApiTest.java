package com.hapbang.auxiliary.chatbot.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.hapbang.auxiliary.chatbot.domain.SyncRun;
import com.hapbang.auxiliary.chatbot.domain.SyncRunStatus;
import com.hapbang.auxiliary.chatbot.domain.SyncTrigger;
import com.hapbang.auxiliary.chatbot.domain.SyncType;
import com.hapbang.auxiliary.chatbot.out.AppearanceApiSyncSource;
import com.hapbang.auxiliary.chatbot.support.CopyRows;
import com.hapbang.shared.appearance.out.AppearanceApiClient;
import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.testsupport.IntegrationTest;
import com.sun.net.httpserver.HttpServer;

/**
 * 실제 출연 내부 API Client로 싱크한다 (#716). 원본 자리에 가짜 출연 HTTP 서버를 두고, 앱이 쓰는 어댑터
 * ({@link AppearanceApiSyncSource})·shared Client·설정({@code hapbang.clients.appearance.base-url})을 그대로 거친다.
 * 다른 싱크 테스트는 어댑터를 가짜로 바꿔 시나리오를 본다.
 */
@IntegrationTest
class ChatbotSyncAppearanceApiTest {

    private static final HttpServer SERVER = start();
    private static final List<String> REQUESTS = new CopyOnWriteArrayList<>();
    private static volatile int status = 200;

    @Autowired
    private ChatbotSyncUseCase chatbotSyncUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void appearanceApi(DynamicPropertyRegistry registry) {
        registry.add("hapbang.clients.appearance.base-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        SERVER.stop(0);
    }

    @BeforeEach
    void reset() {
        REQUESTS.clear();
        status = 200;
        new CopyRows(jdbcTemplate).clear();
    }

    @Test
    @DisplayName("내부 API 키를 붙여 키셋을 끝까지 읽고(afterId=마지막 ID) 사본에 넣는다")
    void pagesThroughApi() {
        SyncRun run = chatbotSyncUseCase.runBatch(SyncType.APPEARANCE, SyncTrigger.SCHEDULED);

        assertThat(run.getStatus()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(run.getScanned()).isEqualTo(2);
        assertThat(run.getInserted()).isEqualTo(2);
        CopyRows rows = new CopyRows(jdbcTemplate);
        assertThat(rows.appearance(1)).containsEntry("result", "COMPLETED").containsEntry("version", 3L);
        assertThat(rows.appearance(2)).containsEntry("result", "SCHEDULED").containsEntry("version", 0L);
        assertThat(REQUESTS).containsExactly(
                "/api/v1/internal/appearances/sync/appearances?size=500 key=" + IntegrationTest.INTERNAL_API_KEY,
                "/api/v1/internal/appearances/sync/appearances?afterId=2&size=500 key=" + IntegrationTest.INTERNAL_API_KEY);
    }

    @Test
    @DisplayName("출연 API가 5xx면 회차를 실패로 남기고 호출 실패 이유를 적는다")
    void apiUnavailable() {
        status = 503;

        SyncRun run = chatbotSyncUseCase.runBatch(SyncType.APPEARANCE_REVIEW, SyncTrigger.SCHEDULED);

        assertThat(run.getStatus()).isEqualTo(SyncRunStatus.FAILED);
        assertThat(run.getError()).contains("출연 내부 API 호출 실패", "UNAVAILABLE", "503");
    }

    @Test
    @DisplayName("출연 API 주소가 없어 Client 빈이 없으면 부를 때 실패한다(싱크 회차가 실패로 남는다)")
    void clientMissing() {
        AppearanceApiSyncSource source = new AppearanceApiSyncSource(
                new StaticListableBeanFactory().getBeanProvider(AppearanceApiClient.class));

        assertThatThrownBy(() -> source.appearances(null, 500))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hapbang.clients.appearance.base-url");
    }

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String uri = exchange.getRequestURI().toString();
                REQUESTS.add(uri + " key=" + exchange.getRequestHeaders().getFirst(InternalApiHeaders.API_KEY));
                byte[] body = (status == 200 ? page(uri) : "").getBytes(StandardCharsets.UTF_8);
                if (body.length > 0) {
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                }
                exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
                if (body.length > 0) {
                    exchange.getResponseBody().write(body);
                }
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // 첫 페이지 2건, 그다음은 빈 마지막 페이지
    private static String page(String uri) {
        if (uri.contains("/sync/appearances") && !uri.contains("afterId")) {
            return """
                    {"statusCode":200,"message":"","data":{"items":[
                    {"id":1,"hostId":10,"guestId":20,"result":"COMPLETED","scheduledAt":"2026-11-20T20:00:00","version":3},
                    {"id":2,"hostId":10,"guestId":21,"result":"SCHEDULED","scheduledAt":"2026-11-21T20:00:00","version":0}],
                    "nextAfterId":2}}""";
        }
        return """
                {"statusCode":200,"message":"","data":{"items":[],"nextAfterId":null}}""";
    }
}
