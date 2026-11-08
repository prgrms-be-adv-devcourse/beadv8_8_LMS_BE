package com.hapbang.appearance.global.config;

import static com.hapbang.testsupport.TraceRequests.get;
import static com.hapbang.testsupport.TraceRequests.randomSpanId;
import static com.hapbang.testsupport.TraceRequests.randomTraceId;
import static com.hapbang.testsupport.TraceRequests.traceparent;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.hapbang.appearance.out.billing.BillingClient;
import com.hapbang.shared.member.out.MemberApiClient;
import com.hapbang.shared.member.out.MemberClientProperties;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.ModuleTestConfiguration;
import com.hapbang.testsupport.PostgresTestcontainersConfig;
import com.hapbang.testsupport.RecordedSpans;
import com.hapbang.testsupport.TraceRequests;
import com.hapbang.testsupport.TraceRequests.StubServer;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.sdk.trace.data.SpanData;

/**
 * Envoy가 넘긴 {@code traceparent}를 이어받아 span을 만드는지 실제 서버로 확인한다 (#631, 강의 #579와 같음).
 * <p>
 * 운영처럼 관리 포트와 내부 API 포트를 따로 둔다({@link ManagementPortTest}와 같은 설정). 샘플링 비율을 1로 올려, 부모가 있는
 * 요청은 부모의 샘플링 결정을 따르는지(parent-based)와 부모 없이 새로 생기는 span이 있는지를 놓치지 않게 한다.
 */
@SpringBootTest(classes = ModuleTestConfiguration.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.config.name=hapbang-module-test",
                "spring.modulith.events.jdbc.schema-initialization.enabled=true",
                "spring.modulith.events.completion-mode=delete",
                "spring.security.oauth2.client.registration.google.client-id=test",
                "spring.security.oauth2.client.registration.google.client-secret=test",
                "hapbang.storage.s3.endpoint=http://localhost:9",
                "hapbang.storage.s3.bucket=test",
                "hapbang.storage.s3.region=ap-northeast-2",
                "hapbang.storage.s3.key-prefix=test",
                "hapbang.internal-api.key=" + IntegrationTest.INTERNAL_API_KEY,
                "management.endpoints.web.exposure.include=health,prometheus",
                "management.endpoint.health.probes.enabled=true",
                "management.prometheus.metrics.export.enabled=true",
                "management.server.port=0",
                // 부모 없이 새로 생기는 span을 놓치지 않게 앱 안에서 시작하는 trace도 모두 샘플링한다
                "management.tracing.sampling.probability=1.0",
                // 이 클래스만 쓰는 컨텍스트(실제 서버)라 연결 풀을 작게 두고 끝나면 닫는다
                "spring.datasource.hikari.maximum-pool-size=2",
                "spring.datasource.hikari.minimum-idle=1"
        })
@Import({PostgresTestcontainersConfig.class, RecordedSpans.Config.class})
// 지갑 내부 API 클라이언트는 이 검사와 관계없다. 다른 출연 통합 테스트(AppearanceE2ETest)처럼 가짜로 둔다
@MockitoBean(types = BillingClient.class)
// 이 설정의 컨텍스트는 다른 테스트와 공유되지 않는다. 남겨 두면 DB 연결을 계속 잡아 테스트 PostgreSQL의 연결 수 상한을 넘긴다
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AppearanceTracingTest {

    private static final String PUBLIC_PATH = "/api/v1/appearances/v3/api-docs";

    private static int internalPort;

    @LocalServerPort
    private int serverPort;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private RecordedSpans spans;

    @Autowired
    private RestClient.Builder restClientBuilder;

    @Autowired
    private ObservationRegistry observationRegistry;

    @DynamicPropertySource
    static void internalApiPort(DynamicPropertyRegistry registry) throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            internalPort = socket.getLocalPort();
        }
        registry.add("hapbang.internal-api.port", () -> internalPort);
    }

    @BeforeEach
    void clearSpans() {
        spans.reset();
    }

    @Test
    void 샘플링된_traceparent로_들어온_요청은_같은_trace의_server_span을_남긴다() throws Exception {
        String traceId = randomTraceId();
        String parentSpanId = randomSpanId();

        get(serverPort, PUBLIC_PATH, traceparent(traceId, parentSpanId, true));

        RecordedSpans.await(() -> !spans.serverSpans(traceId).isEmpty());
        assertThat(spans.serverSpans(traceId)).singleElement()
                .satisfies(span -> assertThat(span.getParentSpanId()).isEqualTo(parentSpanId));
    }

    @Test
    void 샘플링되지_않은_traceparent로_들어온_요청은_span을_남기지_않는다() throws Exception {
        get(serverPort, PUBLIC_PATH, traceparent(randomTraceId(), randomSpanId(), false));

        String barrier = TraceRequests.sampledBarrier(spans, serverPort, PUBLIC_PATH);
        assertThat(spans.except(barrier)).extracting(SpanData::getName).isEmpty();
    }

    @Test
    void 관리_포트의_헬스체크와_지표_요청은_span을_남기지_않는다() throws Exception {
        get(managementPort, "/actuator/health/liveness", traceparent(randomTraceId(), randomSpanId(), true));
        get(managementPort, "/actuator/health/readiness", null);
        get(managementPort, "/actuator/prometheus", null);

        String barrier = TraceRequests.sampledBarrier(spans, serverPort, PUBLIC_PATH);
        assertThat(spans.except(barrier)).extracting(SpanData::getName).isEmpty();
    }

    @Test
    void 내부_API_포트로_들어온_요청도_같은_trace의_server_span을_남긴다() throws Exception {
        String traceId = randomTraceId();
        String parentSpanId = randomSpanId();

        // 내부 API 키가 없어 거절되지만, 요청 span은 응답 코드와 상관없이 남는다
        get(internalPort, "/api/v1/internal/appearances", traceparent(traceId, parentSpanId, true));

        RecordedSpans.await(() -> !spans.serverSpans(traceId).isEmpty());
        assertThat(spans.serverSpans(traceId)).singleElement()
                .satisfies(span -> assertThat(span.getParentSpanId()).isEqualTo(parentSpanId));
    }

    @Test
    void 회원_내부_API_호출은_지금_trace를_이어_traceparent를_보내고_client_span을_남긴다() throws Exception {
        try (StubServer stub = StubServer.respondingWith("""
                {"statusCode":200,"message":"","data":{"items":[],"nextAfterId":null}}""")) {
            // 앱의 실제 Client처럼 Boot가 주입하는 RestClient.Builder로 만든다
            MemberApiClient client = new MemberApiClient(restClientBuilder,
                    new MemberClientProperties(stub.baseUrl(), null, null), "test");
            Observation.createNotStarted("tracing-test", observationRegistry).observe(() -> client.getMembers(null, 10));

            RecordedSpans.await(() -> !spans.clientSpans().isEmpty());
            SpanData parent = spans.named("tracing-test").getFirst();
            SpanData http = spans.clientSpans().getFirst();
            assertThat(http.getTraceId()).isEqualTo(parent.getTraceId());
            assertThat(http.getParentSpanId()).isEqualTo(parent.getSpanId());
            assertThat(stub.traceparents()).singleElement()
                    .satisfies(header -> TraceRequests.assertContinues(header, http));
        }
    }
}
