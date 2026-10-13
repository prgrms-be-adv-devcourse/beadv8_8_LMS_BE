package com.hapbang.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.PostgresTestcontainersConfig;
import com.hapbang.testsupport.TestLogin;

// 실제 actuator 설정(application.yaml의 management.*)과 보안 규칙을 함께 검증한다
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainersConfig.class)
class ActuatorHealthTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    HealthContributorRegistry healthContributorRegistry;

    @Test
    void 헬스체크는_로그인_없이_UP만_응답한다() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void 헬스체크에_DB_구성요소가_등록된다() {
        assertThat(healthContributorRegistry.getContributor("db")).isNotNull();
    }

    @Test
    void 헬스_외_엔드포인트는_로그인해도_404() throws Exception {
        mockMvc.perform(get("/actuator/env").with(TestLogin.as(1L, Role.ADMIN)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/beans").with(TestLogin.as(1L, Role.ADMIN)))
                .andExpect(status().isNotFound());
    }

    @Test
    void 헬스_외_엔드포인트는_비회원이면_401() throws Exception {
        mockMvc.perform(get("/actuator/env"))
                .andExpect(status().isUnauthorized());
    }
}
