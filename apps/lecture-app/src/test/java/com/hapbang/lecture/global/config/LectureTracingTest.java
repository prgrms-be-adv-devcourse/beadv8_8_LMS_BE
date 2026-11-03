package com.hapbang.lecture.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.out.BillingApiClient;
import com.hapbang.shared.billing.out.BillingClientProperties;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.ModuleTestConfiguration;
import com.hapbang.testsupport.PostgresTestcontainersConfig;
import com.sun.net.httpserver.HttpServer;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

/**
 * Envoy가 넘긴 {@code traceparent}를 이어받아 span을 만드는지 실제 서버로 확인한다 (#579).
 * <p>
 * 운영처럼 관리 포트를 따로 둔다({@link ManagementPortTest}와 같은 설정). 샘플링 비율을 1로 올려, 부모가 있는 요청은
 * 부모의 샘플링 결정을 따르는지(parent-based)와 부모 없이 새로 생기는 span이 있는지를 놓치지 않게 한다.
 * span은 만들어질 때마다 바로 메모리로 내보낸다(SimpleSpanProcessor). OTLP endpoint가 없으므로 밖으로는 보내지 않는다.
 */
@SpringBootTest(classes = ModuleTestConfiguration.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.config.name=hapbang-module-test",
                "spring.modulith.events.jdbc.schema-initialization.enabled=true",
                "hapbang.storage.s3.endpoint=http://localhost:9",
                "hapbang.storage.s3.bucket=test",
                "hapbang.storage.s3.region=ap-northeast-2",
                "hapbang.storage.s3.key-prefix=test",
                "hapbang.internal-api.key=" + IntegrationTest.INTERNAL_API_KEY,
                "management.endpoints.web.exposure.include=health,prometheus",
                "management.endpoint.health.probes.enabled=true",
                "management.prometheus.metrics.export.enabled=true",
                "management.server.port=0",
                "management.tracing.sampling.probability=1.0"
        })
@Import({PostgresTestcontainersConfig.class, LectureTracingTest.InMemorySpans.class})
// 이 설정의 컨텍스트는 다른 테스트와 공유되지 않는다. 남겨 두면 DB 연결을 계속 잡아 테스트 PostgreSQL의 연결 수 상한을 넘긴다
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LectureTracingTest {

    private static final InMemorySpanExporter SPANS = InMemorySpanExporter.create();

    private static int internalPort;

    private static HttpClient client;

    @LocalServerPort
    private int serverPort;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private RestClient.Builder restClientBuilder;

    @Autowired
    private ObservationRegistry observationRegistry;

    @TestConfiguration(proxyBeanMethods = false)
    static class InMemorySpans {

        // SpanExporter 빈으로 두면 Boot의 BatchSpanProcessor에도 들어가 늦게 한 번 더 내보낸다. 처리기 빈만 둔다
        @Bean
        SpanProcessor inMemorySpanProcessor() {
            return SimpleSpanProcessor.create(SPANS);
        }
    }

    @BeforeAll
    static void openClient() {
        client = HttpClient.newHttpClient();
    }

    @AfterAll
    static void closeClient() {
        client.close();
    }

    @DynamicPropertySource
    static void internalApiPort(DynamicPropertyRegistry registry) throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            internalPort = socket.getLocalPort();
        }
        registry.add("hapbang.internal-api.port", () -> internalPort);
    }

    @BeforeEach
    void clearSpans() {
        SPANS.reset();
    }

    @Test
    void 샘플링된_traceparent로_들어온_요청은_같은_trace의_server_span을_남긴다() throws Exception {
        String traceId = randomHex(16);
        String parentSpanId = randomHex(8);

        get(serverPort, "/api/v1/lectures", traceparent(traceId, parentSpanId, true));

        await(() -> !serverSpans(traceId).isEmpty());
        assertThat(serverSpans(traceId)).singleElement()
                .satisfies(span -> assertThat(span.getParentSpanId()).isEqualTo(parentSpanId));
    }

    @Test
    void 샘플링되지_않은_traceparent로_들어온_요청은_span을_남기지_않는다() throws Exception {
        String traceId = randomHex(16);

        get(serverPort, "/api/v1/lectures", traceparent(traceId, randomHex(8), false));

        String barrier = sampledBarrierRequest();
        assertThat(SPANS.getFinishedSpanItems()).filteredOn(span -> !span.getTraceId().equals(barrier))
                .extracting(SpanData::getName).isEmpty();
    }

    @Test
    void 관리_포트의_헬스체크와_지표_요청은_span을_남기지_않는다() throws Exception {
        String traceId = randomHex(16);

        get(managementPort, "/actuator/health/liveness", traceparent(traceId, randomHex(8), true));
        get(managementPort, "/actuator/health/readiness", null);
        get(managementPort, "/actuator/prometheus", null);

        String barrier = sampledBarrierRequest();
        assertThat(SPANS.getFinishedSpanItems()).filteredOn(span -> !span.getTraceId().equals(barrier))
                .extracting(SpanData::getName).isEmpty();
    }

    @Test
    void 지갑_호출은_지금_trace를_이어_traceparent를_보내고_client_span을_남긴다() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        HttpServer billing = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        billing.createContext("/", exchange -> {
            received.add(String.valueOf(exchange.getRequestHeaders().getFirst("traceparent")));
            exchange.getRequestBody().readAllBytes();
            byte[] body = """
                    {"statusCode":200,"message":"","data":{
                      "referenceType":"LECTURE_ORDER_ITEM","referenceId":31,"heldHp":10000,"replayed":false}}"""
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        billing.start();
        try {
            // 앱의 실제 Client처럼 Boot가 주입하는 RestClient.Builder로 만든다
            BillingApiClient billingApiClient = new BillingApiClient(restClientBuilder, new BillingClientProperties(
                    "http://127.0.0.1:" + billing.getAddress().getPort(), null, null), "test");

            Observation.createNotStarted("lecture-tracing-test", observationRegistry).observe(() ->
                    billingApiClient.hold(new HoldCommand(7L, BillingReferenceType.LECTURE_ORDER_ITEM, 31L, 10_000L)));
        } finally {
            billing.stop(0);
        }

        await(() -> SPANS.getFinishedSpanItems().stream().anyMatch(span -> span.getKind() == SpanKind.CLIENT));
        SpanData parent = SPANS.getFinishedSpanItems().stream()
                .filter(span -> span.getName().equals("lecture-tracing-test")).findFirst().orElseThrow();
        SpanData http = SPANS.getFinishedSpanItems().stream()
                .filter(span -> span.getKind() == SpanKind.CLIENT).findFirst().orElseThrow();
        assertThat(http.getTraceId()).isEqualTo(parent.getTraceId());
        assertThat(http.getParentSpanId()).isEqualTo(parent.getSpanId());
        // flag는 sampled 비트만 본다. OTel SDK는 random 비트(W3C Trace Context Level 2)도 켜서 03을 보낸다
        assertThat(received).singleElement().asString()
                .startsWith("00-" + http.getTraceId() + "-" + http.getSpanId() + "-")
                .satisfies(header -> assertThat(HexFormat.fromHexDigits(header.substring(header.length() - 2)) & 1)
                        .isEqualTo(1));
    }

    // 샘플링된 요청을 하나 더 보내 그 span이 나올 때까지 기다린다. 앞 요청이 span을 만들었다면 그때까지 나와 있다
    private String sampledBarrierRequest() throws Exception {
        String traceId = randomHex(16);
        get(serverPort, "/api/v1/lectures", traceparent(traceId, randomHex(8), true));
        await(() -> !serverSpans(traceId).isEmpty());
        return traceId;
    }

    private static List<SpanData> serverSpans(String traceId) {
        return SPANS.getFinishedSpanItems().stream()
                .filter(span -> span.getKind() == SpanKind.SERVER && span.getTraceId().equals(traceId))
                .toList();
    }

    private static String traceparent(String traceId, String spanId, boolean sampled) {
        return "00-" + traceId + "-" + spanId + (sampled ? "-01" : "-00");
    }

    private static String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        ThreadLocalRandom.current().nextBytes(value);
        value[0] |= 1;
        return HexFormat.of().formatHex(value);
    }

    private static void get(int port, String path, String traceparent) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (traceparent != null) {
            request.header("traceparent", traceparent);
        }
        HttpResponse<Void> response = client.send(request.build(), HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("5초 안에 조건이 맞아야 한다").isLessThan(deadline);
            Thread.sleep(20);
        }
    }
}
