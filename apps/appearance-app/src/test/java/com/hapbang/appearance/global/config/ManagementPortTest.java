package com.hapbang.appearance.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.hapbang.appearance.out.billing.BillingClient;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.ModuleTestConfiguration;
import com.hapbang.testsupport.PostgresTestcontainersConfig;

/**
 * k8s처럼 관리 포트(MANAGEMENT_SERVER_PORT)와 내부 API 포트를 따로 둔 실제 서버로 포트 분리를 확인한다 (#528, D72).
 * <p>
 * 외부 API 포트에는 Gateway만 들어와야 한다. Prometheus는 관리 포트로만 수집한다. 강의 앱의 같은 이름 테스트(#521)와 같은 검사다.
 * {@code @SpringBootTest}는 하나만 둘 수 있어 팀 공통 {@link IntegrationTest}의 설정 값을 옮겨 둔다(공통 설정이 바뀌면 여기도 맞춘다).
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
                // 운영 application.yaml과 같은 노출 범위. k8s에서 자동으로 켜지는 liveness·readiness 그룹도 켠다
                "management.endpoints.web.exposure.include=health,prometheus",
                "management.endpoint.health.probes.enabled=true",
                // 테스트는 기본으로 지표 내보내기를 끈다. 운영처럼 Prometheus 형식을 연다
                "management.prometheus.metrics.export.enabled=true",
                "management.server.port=0"
        })
@Import(PostgresTestcontainersConfig.class)
// 지갑 내부 API 클라이언트는 이 검사와 관계없다. 다른 출연 통합 테스트(AppearanceE2ETest)처럼 가짜로 둔다
@MockitoBean(types = BillingClient.class)
// 이 설정의 컨텍스트는 다른 테스트와 공유되지 않는다. 남겨 두면 DB 연결을 계속 잡아 테스트 PostgreSQL의 연결 수 상한을 넘긴다
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ManagementPortTest {

    private static int internalPort;

    private static HttpClient client;

    @LocalServerPort
    private int serverPort;

    @LocalManagementPort
    private int managementPort;

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

    @Test
    void 지표와_헬스체크는_관리_포트에서_로그인_없이_응답한다() throws Exception {
        HttpResponse<String> metrics = get(managementPort, "/actuator/prometheus");
        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body()).contains("jvm_memory_used_bytes");

        assertThat(get(managementPort, "/actuator/health/liveness").statusCode()).isEqualTo(200);
        assertThat(get(managementPort, "/actuator/health/readiness").statusCode()).isEqualTo(200);
    }

    @Test
    void 외부_API_포트와_내부_API_포트는_지표를_주지_않는다() throws Exception {
        assertThat(get(serverPort, "/actuator/prometheus").statusCode()).isEqualTo(404);
        assertThat(get(serverPort, "/actuator/health/liveness").statusCode()).isEqualTo(404);
        assertThat(get(internalPort, "/actuator/prometheus").statusCode()).isEqualTo(404);
    }

    @Test
    void 관리_포트는_업무_API를_받지_않는다() throws Exception {
        assertThat(get(managementPort, "/api/v1/appearances/v3/api-docs").statusCode()).isEqualTo(404);
        assertThat(get(serverPort, "/api/v1/appearances/v3/api-docs").statusCode()).isEqualTo(200);
    }

    private static HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
