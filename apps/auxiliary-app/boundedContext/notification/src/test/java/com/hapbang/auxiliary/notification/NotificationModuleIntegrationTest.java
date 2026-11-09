package com.hapbang.auxiliary.notification;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.hapbang.testsupport.IntegrationTest;

/**
 * 알림 모듈만으로 컨텍스트가 뜨고, 모듈 Flyway SQL이 {@code notification} schema와 회원 복제본 테이블을 만드는지 본다(ND-007, ND-046).
 * 팀 공통 {@link IntegrationTest}가 이 모듈 프로젝트의 클래스패스(알림·global·shared)만으로 띄우므로 다른 업무 모듈의 빈·테이블은 없다.
 */
@IntegrationTest
class NotificationModuleIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("모듈 SQL이 notification schema를 만든다")
    void schema() {
        assertThat(jdbcTemplate.queryForList(
                "select schema_name from information_schema.schemata where schema_name = 'notification'", String.class))
                .containsExactly("notification");
    }

    @Test
    @DisplayName("N1은 회원 복제본 테이블 하나만 만든다(ND-046)")
    void onlyReplicaTable() {
        List<String> tables = jdbcTemplate.queryForList(
                "select table_name from information_schema.tables where table_schema = 'notification' order by table_name",
                String.class);

        assertThat(tables).containsExactly("notification_user");
    }

    @Test
    @DisplayName("Flyway 이력은 앱 공통 schema(auxiliary)에 남는다(ND-007)")
    void flywayHistoryInAppSchema() {
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_schema = 'notification' "
                        + "and table_name = 'flyway_schema_history'", Long.class))
                .isZero();
    }
}
