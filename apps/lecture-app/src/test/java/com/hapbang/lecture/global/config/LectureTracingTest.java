package com.hapbang.lecture.global.config;

import static com.hapbang.testsupport.TraceRequests.get;
import static com.hapbang.testsupport.TraceRequests.randomSpanId;
import static com.hapbang.testsupport.TraceRequests.randomTraceId;
import static com.hapbang.testsupport.TraceRequests.traceparent;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;

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

import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.out.BillingApiClient;
import com.hapbang.shared.billing.out.BillingCallResult;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.ModuleTestConfiguration;
import com.hapbang.testsupport.PostgresTestcontainersConfig;
import com.hapbang.testsupport.RecordedSpans;
import com.hapbang.testsupport.TraceRequests;
import com.hapbang.testsupport.billing.RealBillingTestFixture;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.sdk.trace.data.SpanData;

/**
 * Envoy가 넘긴 {@code traceparent}를 이어받아 span을 만드는지 실제 서버로 확인한다 (#579).
 * <p>
 * 운영처럼 관리 포트를 따로 둔다({@link ManagementPortTest}와 같은 설정). 샘플링 비율을 1로 올려, 부모가 있는 요청은
 * 부모의 샘플링 결정을 따르는지(parent-based)와 부모 없이 새로 생기는 span이 있는지를 놓치지 않게 한다.
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
@Import({PostgresTestcontainersConfig.class, RecordedSpans.Config.class})
// 이 설정의 컨텍스트는 다른 테스트와 공유되지 않는다. 남겨 두면 DB 연결을 계속 잡아 테스트 PostgreSQL의 연결 수 상한을 넘긴다
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LectureTracingTest {

    private static final String PUBLIC_PATH = "/api/v1/lectures";

    @LocalServerPort
    private int serverPort;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private RecordedSpans spans;

    @Autowired
    private BillingApiClient billingApiClient;

    @Autowired
    private RealBillingTestFixture billing;

    @Autowired
    private ObservationRegistry observationRegistry;

    @DynamicPropertySource
    static void internalApiPort(DynamicPropertyRegistry registry) throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            int internalPort = socket.getLocalPort();
            registry.add("hapbang.internal-api.port", () -> internalPort);
        }
    }

    @BeforeEach
    void clearSpans() {
        spans.reset();
    }

    @Test
    void 샘플링된_traceparent로_들어온_요청은_같은_trace의_server_span을_남긴다() throws Exception {
        String traceId = randomTraceId();
        String parentSpanId = randomSpanId();

        assertThat(get(serverPort, PUBLIC_PATH, traceparent(traceId, parentSpanId, true))).isEqualTo(200);

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
    void 지갑_호출은_지금_trace를_이어_traceparent를_보내고_client_span을_남긴다() throws Exception {
        billing.wallet(7L, 10_000L, 0L);
        Observation.createNotStarted("lecture-tracing-test", observationRegistry).observe(() -> {
            assertThat(billingApiClient.hold(new HoldCommand(7L, BillingReferenceType.LECTURE_ORDER_ITEM, 31L, 10_000L)))
                    .isInstanceOf(BillingCallResult.Succeeded.class);
        });

        RecordedSpans.await(() -> !spans.clientSpans().isEmpty());
        SpanData parent = spans.named("lecture-tracing-test").getFirst();
        SpanData http = spans.clientSpans().getFirst();
        assertThat(http.getTraceId()).isEqualTo(parent.getTraceId());
        assertThat(http.getParentSpanId()).isEqualTo(parent.getSpanId());
        Map<?, ?> received = RestClient.create(billing.directBaseUrl()).get().uri("/__test/billing/requests/last")
                .header("X-Billing-E2e-Key", "test-billing-e2e-key").retrieve().body(Map.class);
        assertThat(received).isNotNull();
        assertThat(((Number) received.get("count")).longValue()).isEqualTo(1);
        assertThat(received.get("method")).isEqualTo("POST");
        assertThat(received.get("path")).isEqualTo("/api/v1/internal/billing/holds");
        TraceRequests.assertContinues((String) received.get("traceparent"), http);
    }
}
