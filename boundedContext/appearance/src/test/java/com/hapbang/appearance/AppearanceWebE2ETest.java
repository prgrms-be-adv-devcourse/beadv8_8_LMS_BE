package com.hapbang.appearance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.appearance.global.web.AppearanceHeaders;
import com.hapbang.appearance.global.web.LoginMember;
import com.hapbang.testsupport.DatabaseCleanUpListener;
import com.hapbang.testsupport.ModuleTestConfiguration;
import com.hapbang.testsupport.PostgresTestcontainersConfig;

/**
 * 실제 서버를 띄워 HTTP로 호출하고, 출연 모듈의 웹 설정이 앱 안에서 제대로 동작하는지 확인한다.
 * <ul>
 *     <li>{@code @LoginMember} 처리기가 Spring MVC에 등록되었는지</li>
 *     <li>출연 예외가 Problem Detail(JSON, UTF-8)로 응답되는지</li>
 * </ul>
 * 아직 출연 API가 없어서 테스트 전용 컨트롤러를 붙인다. 출연 API가 생기면 실제 API로 흐름을 검증한다. Docker가 필요하다.
 */
@SpringBootTest(classes = ModuleTestConfiguration.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.modulith.events.jdbc.schema-initialization.enabled=true",
                "spring.modulith.events.completion-mode=delete"
        })
@Import({PostgresTestcontainersConfig.class, AppearanceWebE2ETest.ProbeController.class})
@TestExecutionListeners(listeners = DatabaseCleanUpListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class AppearanceWebE2ETest {

    private static final String PROBE_PATH = "/test/appearance/me";

    @LocalServerPort
    private int port;

    private RestTestClient client;

    @BeforeEach
    void setUp() {
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    @DisplayName("X-User-Id 헤더로 호출하면 컨트롤러가 회원 ID를 받는다")
    void resolvesLoginMember() {
        Long memberId = client.get().uri(PROBE_PATH)
                .header(AppearanceHeaders.USER_ID, "7")
                .exchange()
                .expectStatus().isOk()
                .expectBody(Long.class)
                .returnResult()
                .getResponseBody();

        assertThat(memberId).isEqualTo(7L);
    }

    @Test
    @DisplayName("헤더 없이 호출하면 401 Problem Detail(code: LOGIN_REQUIRED)로 응답한다")
    void loginRequired() {
        ProblemDetail problem = client.get().uri(PROBE_PATH)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(ProblemDetail.class)
                .returnResult()
                .getResponseBody();

        assertThat(problem).isNotNull();
        assertThat(problem.getStatus()).isEqualTo(401);
        assertThat(problem.getDetail()).isEqualTo("로그인이 필요합니다.");
        assertThat(problem.getProperties()).containsEntry("code", "LOGIN_REQUIRED");
    }

    /**
     * 테스트 전용 컨트롤러. {@code @TestComponent}라서 다른 테스트의 컴포넌트 스캔에는 잡히지 않고, 이 테스트에서만 {@code @Import}로 붙는다.
     */
    @TestComponent
    @RestController
    static class ProbeController {

        @GetMapping(PROBE_PATH)
        Long me(@LoginMember Long memberId) {
            return memberId;
        }
    }
}
