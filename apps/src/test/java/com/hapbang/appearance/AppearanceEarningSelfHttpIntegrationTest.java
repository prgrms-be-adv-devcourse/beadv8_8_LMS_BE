package com.hapbang.appearance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

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

import com.hapbang.shared.appearance.domain.AppearanceEarningStatus;
import com.hapbang.shared.appearance.dto.AppearanceEarningAmountDto;
import com.hapbang.shared.appearance.dto.AppearanceEarningPageDto;
import com.hapbang.shared.appearance.dto.AppearanceEarningSummaryDto;
import com.hapbang.shared.appearance.out.AppearanceApiCallException;
import com.hapbang.shared.appearance.out.AppearanceApiClient;
import com.hapbang.shared.appearance.out.AppearanceClientProperties;
import com.hapbang.testsupport.DatabaseCleanUpListener;
import com.hapbang.testsupport.PostgresTestcontainersConfig;

/**
 * 다른 모듈(Billing)이 쓰는 shared {@link AppearanceApiClient}가 실제 HTTP로 이 앱의 출연 수익 내부 API를 부르는 전체 경로를 확인한다
 * (D62, #301, 강의 {@code LectureEarningSelfHttpIntegrationTest}와 같은 범위). 내부 API 보안 체인·RsData 해제·직렬화가 맞물리는지 본다.
 * 상태 분류와 응답 필드는 출연 모듈 테스트({@code AppearanceEarningQueryTest}, {@code AppearanceEarningInternalApiTest})가 맡는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestcontainersConfig.class)
@TestExecutionListeners(listeners = DatabaseCleanUpListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class AppearanceEarningSelfHttpIntegrationTest {

    private static final long GUEST = 999_301L;

    @LocalServerPort
    int port;

    @Value("${hapbang.internal-api.key}")
    String internalApiKey;

    @Autowired
    RestClient.Builder restClientBuilder;

    @Autowired
    ApplicationContext applicationContext;

    @Test
    @DisplayName("앱 설정(hapbang.clients.appearance.base-url)으로 Client 빈이 생긴다")
    void clientBean() {
        assertThat(applicationContext.getBeansOfType(AppearanceApiClient.class)).hasSize(1);
    }

    @Test
    @DisplayName("AppearanceApiClient로 자기 자신의 출연 수익 내부 API를 부른다: 출연이 없는 게스트는 0원 요약·빈 페이지")
    void callsSelf() {
        AppearanceApiClient client = client(internalApiKey);

        AppearanceEarningSummaryDto summary = client.getEarningSummary(GUEST);
        AppearanceEarningPageDto page = client.getEarnings(GUEST, List.of(AppearanceEarningStatus.COMPLETED), 0, 20);

        assertThat(List.of(summary.confirmationPending(), summary.scheduled(), summary.onHold(), summary.processing(),
                summary.failed())).containsOnly(AppearanceEarningAmountDto.ZERO);
        assertThat(page.content()).isEmpty();
        assertThat(page.page()).isEqualTo(new AppearanceEarningPageDto.PageMetadata(20, 0, 0, 0));
    }

    @Test
    @DisplayName("키가 틀린 Client는 인증 실패(REJECTED 401) 예외를 받는다")
    void wrongKey() {
        AppearanceApiClient client = client(internalApiKey + "-wrong");

        assertThatThrownBy(() -> client.getEarningSummary(GUEST))
                .isInstanceOfSatisfying(AppearanceApiCallException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(AppearanceApiCallException.Reason.REJECTED);
                    assertThat(e.getStatus()).isEqualTo(401);
                });
    }

    @Test
    @DisplayName("출연 수익 내부 API는 내부 API 키 헤더와 함께 명세(/v3/api-docs)에 나오고, 명세에 키 값은 없다")
    void documented() {
        String docs = restClientBuilder.clone().baseUrl("http://localhost:" + port).build()
                .get().uri("/v3/api-docs").retrieve().body(String.class);

        assertThat(docs).contains("/api/v1/internal/appearances/earnings/summary", "/api/v1/internal/appearances/earnings",
                "X-Internal-Api-Key").doesNotContain(internalApiKey);
    }

    private AppearanceApiClient client(String apiKey) {
        return new AppearanceApiClient(restClientBuilder, new AppearanceClientProperties("http://localhost:" + port,
                Duration.ofSeconds(2), Duration.ofSeconds(5)), apiKey);
    }
}
