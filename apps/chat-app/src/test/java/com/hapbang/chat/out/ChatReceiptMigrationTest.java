package com.hapbang.chat.out;

import static org.assertj.core.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import com.hapbang.chat.fixture.ChatNativeSql;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 수신 확인 순번 마이그레이션은 기존 참여 행을 미확인(NULL)으로 둔다.
 * 읽음 위치나 방의 마지막 순번으로 채우지 않는지, 직전 버전까지 적용한 별도 DB에 기존 데이터를 넣고 확인한다.
 */
@IntegrationTest
class ChatReceiptMigrationTest {

    private static final String VERSION_BEFORE = "202610162130";

    @Autowired
    private Environment environment;

    private ChatMigrationDatabase database;
    private ChatNativeSql sql;

    @BeforeEach
    void createDatabase() {
        database = ChatMigrationDatabase.create(environment);
        sql = database.sql;
    }

    @AfterEach
    void dropDatabase() {
        database.close();
    }

    @Test
    void 기존_참여_행의_수신_확인_순번은_NULL로_남는다() {
        migrate(VERSION_BEFORE);
        sql.update("""
                insert into chats.chat_room (id, room_type, status, last_sequence, created_at, updated_at)
                values (1, 'DIRECT', 'ACTIVE', 7, now(), now())
                """);
        sql.update("""
                insert into chats.chat_room_member (chat_room_id, user_id, joined_at, visible_from_sequence,
                                                    participation_version, last_read_sequence)
                values (1, 10, now(), 1, 1, 7), (1, 20, now(), 3, 2, 5)
                """);

        migrate(null);

        assertThat(sql.rows("""
                select user_id, last_read_sequence, last_received_sequence
                from chats.chat_room_member order by user_id
                """, Map.of()))
                .extracting(row -> row.get("user_id"), row -> row.get("last_read_sequence"),
                        row -> row.get("last_received_sequence"))
                .containsExactly(tuple(10L, 7L, null), tuple(20L, 5L, null));
    }

    /** 앱 설정과 같은 Flyway 설정으로 target까지 적용한다. target이 null이면 최신까지 */
    private void migrate(String target) {
        var configuration = database.flywayConfiguration()
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
