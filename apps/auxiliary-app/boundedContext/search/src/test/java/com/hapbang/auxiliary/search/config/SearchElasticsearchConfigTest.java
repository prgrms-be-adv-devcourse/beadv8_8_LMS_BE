package com.hapbang.auxiliary.search.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.sun.net.httpserver.HttpServer;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Request;

/**
 * 검색 기능 스위치와 검색 전용 client 설정(#771). ES 없이 JDK HTTP 서버로 client가 보내는 인증 헤더를 본다.
 */
class SearchElasticsearchConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SearchElasticsearchConfig.class);

    private HttpServer server;

    private final AtomicReference<String> authorization = new AtomicReference<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("스위치가 없거나 꺼져 있으면 검색 client를 만들지 않는다")
    void switchOff() {
        runner.run(context -> assertThat(context).doesNotHaveBean(SearchElasticsearchConfig.CLIENT));
        runner.withPropertyValues("hapbang.search.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(SearchElasticsearchConfig.CLIENT));
    }

    @Test
    @DisplayName("스위치가 켜지면 검색 client를 만들고, API key가 있으면 ApiKey 인증 헤더를 싣는다")
    void apiKeyHeader() {
        runner.withPropertyValues("hapbang.search.enabled=true", "hapbang.search.elasticsearch.uris=" + serverUri(),
                "hapbang.search.elasticsearch.api-key=c2VhcmNoOmtleQ==").run(context -> {
            assertThat(context).hasBean(SearchElasticsearchConfig.CLIENT);
            call(context.getBean(SearchElasticsearchConfig.CLIENT, ElasticsearchClient.class));

            assertThat(authorization.get()).isEqualTo("ApiKey c2VhcmNoOmtleQ==");
        });
    }

    @Test
    @DisplayName("API key가 비어 있으면(보안을 끈 로컬·테스트) 인증 헤더를 싣지 않는다")
    void noApiKey() {
        runner.withPropertyValues("hapbang.search.enabled=true", "hapbang.search.elasticsearch.uris=" + serverUri(),
                "hapbang.search.elasticsearch.api-key=").run(context -> {
            call(context.getBean(SearchElasticsearchConfig.CLIENT, ElasticsearchClient.class));

            assertThat(authorization.get()).isNull();
        });
    }

    @Test
    @DisplayName("스위치가 켜졌는데 주소가 없으면 기동에 실패한다")
    void requiresUris() {
        runner.withPropertyValues("hapbang.search.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("설정을 문자열로 남겨도 API key는 가린다")
    void masksApiKey() {
        runner.withPropertyValues("hapbang.search.enabled=true", "hapbang.search.elasticsearch.uris=" + serverUri(),
                "hapbang.search.elasticsearch.api-key=secret-value").run(context ->
                assertThat(context.getBean(SearchElasticsearchProperties.class).toString())
                        .doesNotContain("secret-value").contains("apiKey=****"));
    }

    private String serverUri() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void call(ElasticsearchClient client) throws Exception {
        ((Rest5ClientTransport) client._transport()).restClient().performRequest(new Request("GET", "/"));
    }
}
