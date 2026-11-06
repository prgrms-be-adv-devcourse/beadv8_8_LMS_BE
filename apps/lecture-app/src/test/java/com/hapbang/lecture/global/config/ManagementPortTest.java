package com.hapbang.lecture.global.config;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.ModuleTestConfiguration;
import com.hapbang.testsupport.PostgresTestcontainersConfig;

/**
 * k8s처럼 관리 포트(MANAGEMENT_SERVER_PORT)와 내부 API 포트를 따로 둔 실제 서버로 포트 분리를 확인한다 (#521, D72).
 * <p>
 * 앱은 Gateway 사용자 헤더를 믿으므로 외부 API 포트에는 Gateway만 들어와야 한다. Prometheus는 관리 포트로만 수집한다.
 * {@code @SpringBootTest}는 하나만 둘 수 있어 팀 공통 {@link IntegrationTest}의 설정 값을 옮겨 둔다(공통 설정이 바뀌면 여기도 맞춘다).
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
                // 운영 application.yaml과 같은 노출 범위. k8s에서 자동으로 켜지는 liveness·readiness 그룹도 켠다
                "management.endpoints.web.exposure.include=health,prometheus",
                "management.endpoint.health.probes.enabled=true",
                // 테스트는 기본으로 지표 내보내기를 끈다. 운영처럼 Prometheus 형식을 연다
                "management.prometheus.metrics.export.enabled=true",
                "management.server.port=0"
        })
@Import(PostgresTestcontainersConfig.class)
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
        assertThat(get(managementPort, "/api/v1/lectures").statusCode()).isEqualTo(404);
        assertThat(get(serverPort, "/api/v1/lectures").statusCode()).isEqualTo(200);
    }

    // AI 추천용 동기화 내부 API(#712)도 내부 포트로만 받는다. 외부 포트로 오면 키가 맞아도 404다
    @ParameterizedTest
    @ValueSource(strings = {"lectures", "purchases", "cart-items", "reviews"})
    void 동기화_내부_API는_외부_포트에서_404이고_내부_포트에서만_응답한다(String kind) throws Exception {
        String path = "/api/v1/internal/lectures/sync/" + kind;

        assertThat(getInternal(serverPort, path).statusCode()).isEqualTo(404);
        HttpResponse<String> internal = getInternal(internalPort, path);
        assertThat(internal.statusCode()).isEqualTo(200);
        assertThat(internal.body()).contains("\"items\"");
    }

    private static HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> getInternal(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
