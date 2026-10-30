package com.hapbang.chat.out;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.hapbang.testsupport.IntegrationTest;

/**
 * 수신 확인 순번 마이그레이션은 기존 참여 행을 미확인(NULL)으로 둔다.
 * 읽음 위치나 방의 마지막 순번으로 채우지 않는지, 직전 버전까지 적용한 별도 DB에 기존 데이터를 넣고 확인한다.
 */
@IntegrationTest
class ChatReceiptMigrationTest {

    private static final String VERSION_BEFORE = "202610162130";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${spring.datasource.url}")
    private String url;

    @Value("${spring.datasource.username}")
    private String username;

    @Value("${spring.datasource.password}")
    private String password;

    private String database;

    @BeforeEach
    void createDatabase() {
        database = "chat_migration_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.execute("create database " + database);
    }

    @AfterEach
    void dropDatabase() {
        jdbcTemplate.execute("drop database if exists " + database + " with (force)");
    }

    @Test
    void 기존_참여_행의_수신_확인_순번은_NULL로_남는다() {
        String migrationUrl = url.replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
        migrate(migrationUrl, VERSION_BEFORE);
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(migrationUrl, username, password, true);
        try {
            JdbcTemplate migrationJdbc = new JdbcTemplate(dataSource);
            migrationJdbc.update("""
                    insert into chats.chat_room (id, room_type, status, last_sequence, created_at, updated_at)
                    values (1, 'DIRECT', 'ACTIVE', 7, now(), now())
                    """);
            migrationJdbc.update("""
                    insert into chats.chat_room_member (chat_room_id, user_id, joined_at, visible_from_sequence,
                                                        participation_version, last_read_sequence)
                    values (1, 10, now(), 1, 1, 7), (1, 20, now(), 3, 2, 5)
                    """);

            migrate(migrationUrl, null);

            assertThat(migrationJdbc.queryForList("""
                    select user_id, last_read_sequence, last_received_sequence
                    from chats.chat_room_member order by user_id
                    """))
                    .extracting(row -> row.get("user_id"), row -> row.get("last_read_sequence"),
                            row -> row.get("last_received_sequence"))
                    .containsExactly(tuple(10L, 7L, null), tuple(20L, 5L, null));
        } finally {
            dataSource.destroy();
        }
    }

    /** 앱 설정과 같은 Flyway 설정으로 target까지 적용한다. target이 null이면 최신까지 */
    private void migrate(String migrationUrl, String target) {
        var configuration = Flyway.configure()
                .dataSource(migrationUrl, username, password)
                .locations("classpath:db/migration")
                .schemas("chat")
                .defaultSchema("chat")
                .outOfOrder(true);
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }
}
