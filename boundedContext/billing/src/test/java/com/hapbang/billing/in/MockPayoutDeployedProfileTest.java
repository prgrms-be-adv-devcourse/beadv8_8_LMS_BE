package com.hapbang.billing.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.billing.app.MockPayoutBank;
import com.hapbang.billing.app.MockPayoutScenarios;
import com.hapbang.billing.out.MockPayoutAccessKey;
import com.hapbang.billing.out.MockPayoutActivation;
import com.hapbang.billing.out.MockPayoutHttpClient;
import com.hapbang.billing.out.PayoutTransferRequest;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.TestLogin;

// 활성 프로필이 전부 local·test가 아닌 환경(prod·dev, local+staging, 프로필 미지정, 기본 프로필만 local)에 가상 송금 Mock 설정(enabled=true)이 들어가도 Mock 은행 API·시나리오 제어·전용 보안 체인이 생기지 않고
// 송금 Client도 HTTP를 보내지 않는지 확인한다(#288). URL 숨김·Swagger 제외가 아니라 빈 자체가 없다.
// 프로필마다 컨텍스트가 따로 생기므로 연결 풀을 작게 두고 클래스가 끝나면 닫는다(전체 실행에서 DB 최대 연결 수를 넘지 않게).
class MockPayoutDeployedProfileTest {

    @Nested
    @IntegrationTest
    @AutoConfigureMockMvc
    @ActiveProfiles("prod")
    @TestPropertySource(properties = {"hapbang.billing.mock-payout.enabled=true",
            "spring.datasource.hikari.maximum-pool-size=3", "spring.datasource.hikari.minimum-idle=1"})
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class Prod extends Checks {
    }

    @Nested
    @IntegrationTest
    @AutoConfigureMockMvc
    @ActiveProfiles("dev")
    @TestPropertySource(properties = {"hapbang.billing.mock-payout.enabled=true",
            "spring.datasource.hikari.maximum-pool-size=3", "spring.datasource.hikari.minimum-idle=1"})
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class Dev extends Checks {
    }

    // 허용 프로필(local)에 허용 목록 밖 프로필(staging)이 섞이면 enabled=true여도 꺼진다
    @Nested
    @IntegrationTest
    @AutoConfigureMockMvc
    @ActiveProfiles({"local", "staging"})
    @TestPropertySource(properties = {"hapbang.billing.mock-payout.enabled=true",
            "spring.datasource.hikari.maximum-pool-size=3", "spring.datasource.hikari.minimum-idle=1"})
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class LocalWithStaging extends Checks {
    }

    // 프로필을 지정하지 않으면(모듈 테스트 기본 상태) enabled=true여도 꺼진다
    @Nested
    @IntegrationTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = {"hapbang.billing.mock-payout.enabled=true",
            "spring.datasource.hikari.maximum-pool-size=3", "spring.datasource.hikari.minimum-idle=1"})
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class NoProfile extends Checks {
    }

    // 애플리케이션처럼 기본 프로필만 local이고(spring.profiles.default=local) 활성 프로필이 없으면 꺼진다
    @Nested
    @IntegrationTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = {"hapbang.billing.mock-payout.enabled=true", "spring.profiles.default=local",
            "spring.datasource.hikari.maximum-pool-size=3", "spring.datasource.hikari.minimum-idle=1"})
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class DefaultProfileLocal extends Checks {
    }

    abstract static class Checks {

        @Autowired
        ApplicationContext context;

        @Autowired
        MockMvc mockMvc;

        @Autowired
        MockPayoutHttpClient client;

        @Autowired
        JdbcTemplate jdbcTemplate;

        @Test
        @DisplayName("설정이 켜져 있어도 Mock 은행·시나리오 제어·컨트롤러·전용 보안 체인 빈이 없고 Client는 HTTP 없이 거절한다")
        void mockStaysOff() {
            assertThat(context.getEnvironment().getProperty("hapbang.billing.mock-payout.enabled")).isEqualTo("true");
            assertThat(MockPayoutActivation.isActive(context.getEnvironment())).isFalse();
            assertThat(context.getBeansOfType(MockPayoutBank.class)).isEmpty();
            assertThat(context.getBeansOfType(MockPayoutScenarios.class)).isEmpty();
            assertThat(context.getBeansOfType(MockPayoutController.class)).isEmpty();
            assertThat(context.containsBean("mockPayoutFilterChain")).isFalse();
            assertThat(client.isAvailable()).isFalse();
            PayoutTransferRequest request = new PayoutTransferRequest(UUID.randomUUID().toString(), 1L, 1_000L, "004",
                    "12345678", "홍길동");
            assertThatThrownBy(() -> client.send(request)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> client.find(request.payoutRequestId())).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("경로가 없다: 접근 키 헤더를 보내도 비로그인 401, 로그인 404이고 Mock 기록이 생기지 않는다")
        void endpointAbsent() throws Exception {
            String body = "{\"payoutRequestId\":\"" + UUID.randomUUID() + "\",\"exchangeId\":1,\"amountKrw\":1000,"
                    + "\"bankCode\":\"004\",\"accountNumber\":\"12345678\",\"accountHolderName\":\"홍길동\"}";

            // CSRF 토큰이 없으면 global 보안이 그 전에 403으로 막는다. 토큰을 넣어 인증 단계(401)까지 확인한다
            mockMvc.perform(post("/api/v1/wallets/mock-payouts").header(MockPayoutAccessKey.HEADER, "any")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/v1/wallets/mock-payouts").with(csrf()).header(MockPayoutAccessKey.HEADER, "any")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(post("/api/v1/wallets/mock-payouts").with(csrf()).with(TestLogin.as(1L, Role.ADMIN))
                            .header(MockPayoutAccessKey.HEADER, "any")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/api/v1/wallets/mock-payouts/" + UUID.randomUUID())
                            .with(TestLogin.as(1L, Role.INFLUENCER)))
                    .andExpect(status().isNotFound());
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.mock_payout_transfer", Integer.class))
                    .isZero();
        }
    }
}
