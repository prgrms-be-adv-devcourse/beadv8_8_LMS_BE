package com.hapbang.auxiliary.chatbot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.hapbang.testsupport.IntegrationTest;

/**
 * 챗봇 모듈 통합 테스트의 기본 형태. 팀 공통 {@link IntegrationTest}가 이 모듈 프로젝트의 클래스패스(챗봇·global·shared)만으로
 * 컨텍스트를 띄우고, 이 모듈의 Flyway SQL만 실행한다. 다른 업무 모듈의 빈·테이블은 들어오지 않는다.
 */
@IntegrationTest
class ChatbotModuleIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("모듈만으로 컨텍스트가 뜨고, 모듈 SQL이 chatbot schema를 만든다")
    void moduleContext() {
        assertThat(jdbcTemplate.queryForList(
                "select schema_name from information_schema.schemata where schema_name = 'chatbot'", String.class))
                .containsExactly("chatbot");
    }
}
