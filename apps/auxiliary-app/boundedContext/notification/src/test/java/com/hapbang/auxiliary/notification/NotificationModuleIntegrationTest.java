package com.hapbang.auxiliary.notification;

import static org.assertj.core.api.Assertions.*;

import java.util.Arrays;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
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

    @Autowired
    private Flyway flyway;

    @Test
    @DisplayName("모듈 SQL이 notification schema를 만든다")
    void schema() {
        assertThat(jdbcTemplate.queryForList(
                "select schema_name from information_schema.schemata where schema_name = 'notification'", String.class))
                .containsExactly("notification");
    }

    @Test
    @DisplayName("테이블은 처음 쓰는 작업에서 만든다: N5까지 알림, 운영 설정(ND-095), 회원 복제본 셋이다(ND-046)")
    void tablesSoFar() {
        List<String> tables = jdbcTemplate.queryForList(
                "select table_name from information_schema.tables where table_schema = 'notification' order by table_name",
                String.class);

        assertThat(tables).containsExactly("notification", "notification_runtime_setting", "notification_user");
    }

    /**
     * 모듈 테스트는 앱 설정({@code application.yaml}의 Flyway 기본 schema {@code auxiliary})을 읽지 않으므로 이력 위치는 여기서 보지 않는다.
     * 이력이 {@code auxiliary}에 남는지는 앱 기동 테스트({@code AuxiliaryApplicationTest#flywaySchemas})가 본다.
     */
    @Test
    @DisplayName("모듈 Flyway SQL(create notification schema)이 성공으로 적용되고, notification schema에는 이력 테이블을 만들지 않는다")
    void moduleMigrationApplied() {
        assertThat(Arrays.stream(flyway.info().applied())
                .filter(migration -> migration.getVersion() != null
                        && "202611091500".equals(migration.getVersion().getVersion())))
                .singleElement().satisfies(migration -> {
                    assertThat(migration.getDescription()).isEqualTo("create notification schema");
                    assertThat(migration.getState()).isEqualTo(MigrationState.SUCCESS);
                });
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_schema = 'notification' "
                        + "and table_name = 'flyway_schema_history'", Long.class))
                .isZero();
    }
}
