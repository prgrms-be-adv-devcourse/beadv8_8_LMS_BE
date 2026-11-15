package com.hapbang.testsupport.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.sun.net.httpserver.HttpServer;

/** 금융 응답을 흉내 내지 않고 전송 계층의 전달·유실 경계를 실제 소켓으로 검증한다. */
class BillingTransportTest {
    private HttpServer upstream;
    private BillingTransport transport;
    private ExecutorService executor;
    private final AtomicInteger handled = new AtomicInteger();

    @AfterEach
    void stop() {
        if (transport != null) transport.close();
        if (upstream != null) upstream.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    @Test
    void 실제_서버의_상태와_헤더와_본문을_그대로_전달한다() throws Exception {
        start(201, "upstream-response");
        try (HttpClient client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create(transport.baseUrl() + "/actual")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(201);
            assertThat(response.headers().firstValue("X-Upstream")).contains("actual-server");
            assertThat(response.body()).isEqualTo("upstream-response");
            assertThat(handled.get()).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @MethodSource("responses")
    void 응답을_잃으면_본문길이와_관계없이_서버처리_후_상태와_본문이_전달되지_않는다(int status, String body) throws Exception {
        start(status, body);
        transport.dropResponses(1);
        URI uri = URI.create(transport.baseUrl());
        // HTTP 라이브러리의 자동 재전송과 분리해 물리 요청 한 번의 전달 유실을 관찰한다.
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), 5_000);
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write(("GET /actual HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            assertThat(socket.getInputStream().readAllBytes()).isEmpty();
        }
        assertThat(handled.get()).isEqualTo(1);
    }

    private static Stream<Arguments> responses() {
        return Stream.of(Arguments.of(204, ""), Arguments.of(200, "x"), Arguments.of(200, "payload"));
    }

    @Test
    void 종료하면_프록시가_새_연결을_받지_않고_다시_열리지_않는다() throws Exception {
        start(200, "upstream-response");
        URI uri = URI.create(transport.baseUrl());
        transport.close();
        assertThatThrownBy(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), 1_000);
            }
        }).isInstanceOf(IOException.class);
        assertThatThrownBy(transport::reconnect).isInstanceOf(IllegalStateException.class);
        assertThat(handled.get()).isZero();
    }

    private void start(int status, String body) throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        upstream.setExecutor(executor);
        upstream.createContext("/", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                handled.incrementAndGet();
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("X-Upstream", "actual-server");
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            }
        });
        upstream.start();
        transport = new BillingTransport("http://127.0.0.1:" + upstream.getAddress().getPort());
    }
}
