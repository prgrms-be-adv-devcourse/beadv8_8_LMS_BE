package com.hapbang.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.web.client.RestClient;

import com.hapbang.member.domain.User;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.dto.MemberPageDto;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.shared.member.out.MemberApiCallException;
import com.hapbang.shared.member.out.MemberApiClient;
import com.hapbang.shared.member.out.MemberClientProperties;
import com.hapbang.testsupport.DatabaseCleanUpListener;
import com.hapbang.testsupport.PostgresTestcontainersConfig;

/**
 * 다른 모듈(Billing)이 쓰는 shared {@link MemberApiClient}가 실제 HTTP로 이 앱의 회원 내부 API를 부르는 전체 경로를 확인한다
 * (D62, #303, 출연 {@code AppearanceEarningSelfHttpIntegrationTest}와 같은 범위). 내부 API 보안 체인·RsData 해제·직렬화가 맞물리는지 본다.
 * 키셋 경계·역할·상태·400 같은 세부 규칙은 회원 모듈 테스트({@code MemberInternalApiTest})가 맡는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestcontainersConfig.class)
@TestExecutionListeners(listeners = DatabaseCleanUpListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class MemberSelfHttpIntegrationTest {

    @LocalServerPort
    int port;

    @Value("${hapbang.internal-api.key}")
    String internalApiKey;

    @Autowired
    RestClient.Builder restClientBuilder;

    @Autowired
    ApplicationContext applicationContext;

    @Autowired
    UserRepository userRepository;

    @Test
    @DisplayName("앱 설정(hapbang.clients.member.base-url)으로 Client 빈이 생긴다")
    void clientBean() {
        assertThat(applicationContext.getBeansOfType(MemberApiClient.class)).hasSize(1);
    }

    @Test
    @DisplayName("MemberApiClient로 자기 자신의 회원 내부 API를 불러 회원을 버전과 함께 키셋 페이지로 받는다")
    void callsSelf() {
        User first = userRepository.save(User.join("self-http-1@example.com", "셀프회원1"));
        User second = userRepository.save(User.join("self-http-2@example.com", "셀프회원2"));
        User third = userRepository.save(User.join("self-http-3@example.com", "셀프회원3"));
        MemberApiClient client = client(internalApiKey);

        MemberPageDto firstPage = client.getMembers(first.getId() - 1, 2);
        MemberPageDto lastPage = client.getMembers(firstPage.nextAfterId(), 2);

        assertThat(firstPage.items()).containsExactly(first.toDto(), second.toDto());
        assertThat(firstPage.nextAfterId()).isEqualTo(second.getId());
        assertThat(lastPage.items()).extracting(UserDto::id).containsExactly(third.getId());
        assertThat(lastPage.items().getFirst().version()).isZero();
        assertThat(lastPage.nextAfterId()).isNull();
    }

    @Test
    @DisplayName("키가 틀린 Client는 인증 실패(REJECTED 401) 예외를 받는다")
    void wrongKey() {
        MemberApiClient client = client(internalApiKey + "-wrong");

        assertThatThrownBy(() -> client.getMembers(null, 10))
                .isInstanceOfSatisfying(MemberApiCallException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(MemberApiCallException.Reason.REJECTED);
                    assertThat(e.getStatus()).isEqualTo(401);
                });
    }

    @Test
    @DisplayName("회원 내부 API는 내부 API 키 헤더와 함께 명세(/v3/api-docs)에 나오고, 명세에 키 값은 없다")
    void documented() {
        String docs = restClientBuilder.clone().baseUrl("http://localhost:" + port).build()
                .get().uri("/v3/api-docs").retrieve().body(String.class);

        assertThat(docs).contains("/api/v1/internal/members", "X-Internal-Api-Key").doesNotContain(internalApiKey);
    }

    private MemberApiClient client(String apiKey) {
        return new MemberApiClient(restClientBuilder, new MemberClientProperties("http://localhost:" + port,
                Duration.ofSeconds(2), Duration.ofSeconds(5)), apiKey);
    }
}
