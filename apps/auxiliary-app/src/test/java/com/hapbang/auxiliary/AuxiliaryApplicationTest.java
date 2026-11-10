package com.hapbang.auxiliary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.hapbang.auxiliary.search.config.SearchElasticsearchConfig;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.PostgresTestcontainersConfig;
import com.hapbang.testsupport.RedisTestcontainersConfig;

/**
 * 운영 설정(application.yaml)으로 앱 전체를 띄워 뼈대를 확인한다(#714).
 * <ul>
 *     <li>Flyway가 앱 공통 schema(auxiliary)에 이력을 남기고 모듈 SQL로 chatbot schema를 만든다.</li>
 *     <li>k8s처럼 관리 포트·내부 API 포트를 따로 둔다. 헬스체크·지표는 관리 포트에서만, 내부 API는 내부 포트에서만 받는다.</li>
 *     <li>Elasticsearch·LLM 없이 뜬다(#715). 이 테스트에는 ES가 없고 모델은 꺼져 있다. 그래도 liveness·readiness는 UP이고
 *     FAQ 시드가 들어가며 FAQ 목록·대화(fallback)가 동작한다.</li>
 *     <li>검색 기능 스위치(#771)가 꺼진 운영 기본값으로 뜬다. 검색 모듈 빈은 없고 schema만 생긴다.</li>
 * </ul>
 * 로컬 프로필(docker compose, .env)을 읽지 않도록 다른 프로필로 띄운다. 업무 모듈 테스트는 각 모듈 프로젝트에 둔다.
 */
@SpringBootTest(classes = AuxiliaryApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.profiles.active=test",
                "hapbang.internal-api.key=" + IntegrationTest.INTERNAL_API_KEY,
                "hapbang.scheduling.enabled=false",
                // 이 테스트는 Kafka 없이 앱 뼈대(포트·Flyway·헬스)를 본다. 알림 회원 이벤트 소비자는 끄고, 소비는 알림 모듈 Kafka 테스트가 본다
                "hapbang.notification.member-events.enabled=false",
                // 챗봇 사본 이벤트 소비(출연·회원·강의, #716·#717)는 Kafka가 없는 이 테스트에서 끈다. 소비는 챗봇 모듈 Kafka 테스트가 본다
                "hapbang.chatbot.copy-events.enabled=false",
                // 운영 설정은 다른 앱 내부 API 주소를 환경 변수로만 받는다(기본값 없음). 이 테스트는 부르지 않는다
                "hapbang.clients.appearance.base-url=http://localhost:1",
                "hapbang.clients.member.base-url=http://localhost:1",
                "hapbang.clients.lecture.base-url=http://localhost:1",
                // k8s에서 자동으로 켜지는 liveness·readiness 그룹을 켠다
                "management.endpoint.health.probes.enabled=true",
                // 테스트는 기본으로 지표 내보내기를 끈다. 운영처럼 Prometheus 형식을 연다
                "management.prometheus.metrics.export.enabled=true",
                "management.server.port=0",
                // 어느 구성 요소가 DOWN인지 본다(운영은 never)
                "management.endpoint.health.show-components=always",
                // ES가 없는 주소. 연결 거절을 바로 받는다
                "spring.elasticsearch.uris=http://127.0.0.1:9"
        })
// Redis 연결은 common redis 자동 설정이라 헬스체크에 들어간다. 운영처럼 Redis도 띄워 전체 상태가 UP인지 본다
@Import({PostgresTestcontainersConfig.class, RedisTestcontainersConfig.class})
// 이 설정의 컨텍스트는 다른 테스트와 공유되지 않는다. 남겨 두면 DB 연결을 계속 잡는다
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuxiliaryApplicationTest {

    private static int internalPort;

    private static HttpClient client;

    @LocalServerPort
    private int serverPort;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationContext applicationContext;

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
    @DisplayName("Flyway 이력은 auxiliary schema에 남고, 챗봇·알림·검색 모듈 SQL이 chatbot·notification·search schema를 만든다")
    void flywaySchemas() {
        assertThat(jdbcTemplate.queryForList(
                "select schema_name from information_schema.schemata "
                        + "where schema_name in ('auxiliary', 'chatbot', 'notification', 'search')",
                String.class)).containsExactlyInAnyOrder("auxiliary", "chatbot", "notification", "search");
        assertThat(jdbcTemplate.queryForList(
                "select description from auxiliary.flyway_schema_history where success", String.class))
                .contains("create chatbot schema", "create notification schema", "create search schema");
        // 알림 회원 복제본 테이블(ND-046)
        assertThat(jdbcTemplate.queryForList(
                "select table_name from information_schema.tables "
                        + "where table_schema = 'notification' and table_name = 'notification_user'",
                String.class)).containsExactly("notification_user");
    }

    @Test
    @DisplayName("검색 기능 스위치가 꺼져 있으면(운영 기본값) 검색 모듈 패키지의 빈이 하나도 없고 앱은 뜬다(새 빈에 스위치를 빠뜨리면 실패)")
    void searchSwitchOff() {
        String searchPackage = SearchElasticsearchConfig.class.getPackageName().replace(".config", ".");
        assertThat(Arrays.stream(applicationContext.getBeanDefinitionNames())
                .map(applicationContext::getType)
                .filter(type -> type != null && type.getName().startsWith(searchPackage))
                .map(Class::getName))
                .isEmpty();
        assertThat(applicationContext.containsBean(SearchElasticsearchConfig.CLIENT)).isFalse();
    }

    @Test
    @DisplayName("헬스체크는 관리 포트에서 로그인 없이 열린다. ES가 죽어도 liveness·readiness는 UP이고 전체 상태만 ES 때문에 DOWN이다")
    void healthOnManagementPort() throws Exception {
        HttpResponse<String> health = get(managementPort, "/actuator/health");
        assertThat(health.statusCode()).isEqualTo(503);
        assertThat(health.body()).contains("\"elasticsearch\":{\"status\":\"DOWN\"}", "\"db\":{\"status\":\"UP\"}",
                "\"redis\":{\"status\":\"UP\"}");
        assertThat(get(managementPort, "/actuator/health/liveness").statusCode()).isEqualTo(200);
        assertThat(get(managementPort, "/actuator/health/readiness").statusCode()).isEqualTo(200);
        HttpResponse<String> metrics = get(managementPort, "/actuator/prometheus");
        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body()).contains("jvm_memory_used_bytes");
    }

    @Test
    @DisplayName("시작할 때 FAQ 시드 53건을 넣는다. ES·LLM이 없어도 FAQ 목록은 동작하고 대화는 fallback으로 답한다")
    void chatbotWithoutElasticsearchAndModel() throws Exception {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(
                jdbcTemplate.queryForObject("select count(*) from chatbot.faq", Long.class)).isEqualTo(53));

        HttpResponse<String> list = send(HttpRequest.newBuilder(uri(serverPort, "/api/v1/chatbot/faqs?category=WITHDRAW"))
                .header("X-User-Id", "1").header("X-User-Role", "USER").GET());
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains("FAQ-951", "FAQ-952");

        HttpResponse<String> chat = send(HttpRequest.newBuilder(uri(serverPort, "/api/v1/chatbot/chat/faq"))
                .header("X-User-Id", "1").header("X-User-Role", "USER").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"탈퇴는 어떻게 해요?\"}")));
        assertThat(chat.statusCode()).isEqualTo(200);
        assertThat(chat.body()).contains("\"fallback\":true", "고객센터 문의");

        assertThat(send(HttpRequest.newBuilder(uri(serverPort, "/api/v1/chatbot/faqs")).GET()).statusCode())
                .isEqualTo(401);
    }

    @Test
    @DisplayName("외부 API 포트와 내부 API 포트는 헬스체크·지표를 주지 않는다")
    void noActuatorOnApiPorts() throws Exception {
        assertThat(get(serverPort, "/actuator/health").statusCode()).isEqualTo(404);
        assertThat(get(serverPort, "/actuator/prometheus").statusCode()).isEqualTo(404);
        assertThat(get(internalPort, "/actuator/prometheus").statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("내부 API 경로는 외부 API 포트에서 404이고, 내부 API 포트에서는 키가 없으면 401이다")
    void internalApiOnlyOnInternalPort() throws Exception {
        assertThat(get(serverPort, "/api/v1/internal/chatbot/probe").statusCode()).isEqualTo(404);
        assertThat(get(internalPort, "/api/v1/internal/chatbot/probe").statusCode()).isEqualTo(401);
        assertThat(get(internalPort, "/api/v1/auxiliary/v3/api-docs").statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("Swagger는 외부 API 포트의 앱 경로(/api/v1/auxiliary)에서 로그인 없이 열리고, 관리 포트에서는 열리지 않는다")
    void swaggerOnServerPort() throws Exception {
        assertThat(get(serverPort, "/api/v1/auxiliary/v3/api-docs").statusCode()).isEqualTo(200);
        assertThat(get(managementPort, "/api/v1/auxiliary/v3/api-docs").statusCode()).isEqualTo(404);
    }

    private static HttpResponse<String> get(int port, String path) throws Exception {
        return send(HttpRequest.newBuilder(uri(port, path)).GET());
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static URI uri(int port, String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
