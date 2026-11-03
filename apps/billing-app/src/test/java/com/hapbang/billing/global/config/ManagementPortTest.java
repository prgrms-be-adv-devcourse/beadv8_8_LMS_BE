package com.hapbang.billing.global.config;

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

import com.hapbang.billing.global.auth.GatewayUserHeaderFilter;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.ModuleTestConfiguration;
import com.hapbang.testsupport.PostgresTestcontainersConfig;

/**
 * k8s처럼 관리 포트(MANAGEMENT_SERVER_PORT)와 내부 API 포트를 따로 둔 실제 서버로 포트 분리를 확인한다 (#552, 강의 #521과 같음).
 * <p>
 * 앱은 Gateway 사용자 헤더를 믿으므로 외부 API 포트에는 Gateway만 들어와야 한다. Prometheus는 관리 포트로만 수집한다.
 * {@code @SpringBootTest}는 하나만 둘 수 있어 팀 공통 {@link IntegrationTest}의 설정 값을 옮겨 둔다(공통 설정이 바뀌면 여기도 맞춘다).
 */
@SpringBootTest(classes = ModuleTestConfiguration.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.config.name=hapbang-module-test",
                "spring.modulith.events.jdbc.schema-initialization.enabled=true",
                "spring.modulith.events.completion-mode=delete",
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
                "management.server.port=0",
                // 이 클래스만 쓰는 컨텍스트(실제 서버)라 연결 풀을 작게 두고 끝나면 닫는다(다른 테스트 컨텍스트와 함께 DB 최대 연결 수를 넘지 않게)
                "spring.datasource.hikari.maximum-pool-size=2",
                "spring.datasource.hikari.minimum-idle=1"
        })
@Import(PostgresTestcontainersConfig.class)
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
        assertThat(metrics.body()).contains("jvm_memory_used_bytes").contains("hikaricp_connections_max");
        // 나가는 호출 회로 4개의 상태 지표 (#562)
        assertThat(metrics.body()).contains("resilience4j_circuitbreaker_state{name=\"toss\"")
                .contains("resilience4j_circuitbreaker_state{name=\"lecture\"")
                .contains("resilience4j_circuitbreaker_state{name=\"appearance\"")
                .contains("resilience4j_circuitbreaker_state{name=\"member\"");
        // 배치·Backfill 실행 락이 건너뛴 횟수 (#598). 건너뛴 일이 없어도 작업·이유마다 0으로 나온다
        assertThat(metrics.body()).contains("hapbang_billing_job_lock_skipped_total{job=\"charge-recovery\",reason=\"held\"}")
                .contains("hapbang_billing_job_lock_skipped_total{job=\"member-backfill\",reason=\"error\"}");

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
        // 관리 포트에도 보안 필터가 있어 비로그인이면 경로를 찾기 전에 401이다. 로그인해도 업무 API가 없어 404다
        assertThat(get(managementPort, "/api/v1/wallets/me").statusCode()).isEqualTo(401);
        assertThat(getAsAdmin(managementPort, "/api/v1/settlements/admin/platform-wallet").statusCode())
                .isEqualTo(404);
        // 외부 API 포트는 업무 API를 받는다(비로그인 401, 관리자 200)
        assertThat(get(serverPort, "/api/v1/wallets/me").statusCode()).isEqualTo(401);
        assertThat(getAsAdmin(serverPort, "/api/v1/settlements/admin/platform-wallet").statusCode()).isEqualTo(200);
    }

    private static HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> getAsAdmin(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header(GatewayUserHeaderFilter.USER_ID, "1")
                .header(GatewayUserHeaderFilter.USER_ROLE, "ADMIN")
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
