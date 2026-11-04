package com.hapbang.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

import com.sun.net.httpserver.HttpServer;

import io.opentelemetry.sdk.trace.data.SpanData;

/**
 * trace 계측 테스트의 요청 도우미 (#579, #631). Envoy처럼 W3C {@code traceparent}를 붙여 앱에 요청하고, 앱이 다른 앱을 부를 때
 * 보낸 {@code traceparent}를 가짜 서버로 받는다.
 */
public final class TraceRequests {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private TraceRequests() {
    }

    public static String randomTraceId() {
        return randomHex(16);
    }

    public static String randomSpanId() {
        return randomHex(8);
    }

    public static String traceparent(String traceId, String spanId, boolean sampled) {
        return "00-" + traceId + "-" + spanId + (sampled ? "-01" : "-00");
    }

    /** {@code traceparent}(없으면 null)를 붙여 GET을 보내고 상태 코드를 돌려준다. span은 응답 코드와 상관없이 생긴다 */
    public static int get(int port, String path, String traceparent) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (traceparent != null) {
            request.header("traceparent", traceparent);
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    /**
     * 샘플링된 요청을 하나 더 보내 그 server span이 나올 때까지 기다리고 그 trace ID를 돌려준다. 앞 요청이 span을 만들었다면
     * 그때까지 나와 있으므로, "span이 없어야 한다"를 확인하기 전에 장벽으로 쓴다.
     */
    public static String sampledBarrier(RecordedSpans spans, int port, String path) throws Exception {
        String traceId = randomTraceId();
        get(port, path, traceparent(traceId, randomSpanId(), true));
        RecordedSpans.await(() -> !spans.serverSpans(traceId).isEmpty());
        return traceId;
    }

    /** 보낸 {@code traceparent}가 client span을 부모로 같은 trace를 잇고 샘플링 비트가 켜져 있는지 확인한다 */
    public static void assertContinues(String traceparent, SpanData clientSpan) {
        // flag는 sampled 비트만 본다. OTel SDK는 random 비트(W3C Trace Context Level 2)도 켜서 03을 보낸다
        assertThat(traceparent)
                .startsWith("00-" + clientSpan.getTraceId() + "-" + clientSpan.getSpanId() + "-")
                .satisfies(header -> assertThat(HexFormat.fromHexDigits(header.substring(header.length() - 2)) & 1)
                        .isEqualTo(1));
    }

    private static String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        ThreadLocalRandom.current().nextBytes(value);
        value[0] |= 1;
        return HexFormat.of().formatHex(value);
    }

    /**
     * 다른 앱의 내부 API 대신 서는 가짜 서버. 모든 요청에 같은 JSON 본문으로 200을 답하고 받은 {@code traceparent}를 모은다.
     */
    public static final class StubServer implements AutoCloseable {

        private final HttpServer server;

        private final List<String> traceparents = new CopyOnWriteArrayList<>();

        private StubServer(String jsonBody) throws IOException {
            byte[] body = jsonBody.getBytes(StandardCharsets.UTF_8);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                traceparents.add(String.valueOf(exchange.getRequestHeaders().getFirst("traceparent")));
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
        }

        public static StubServer respondingWith(String jsonBody) throws IOException {
            return new StubServer(jsonBody);
        }

        public String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        public List<String> traceparents() {
            return traceparents;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
