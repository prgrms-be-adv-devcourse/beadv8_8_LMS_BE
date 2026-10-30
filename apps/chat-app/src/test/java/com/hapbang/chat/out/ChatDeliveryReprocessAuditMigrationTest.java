package com.hapbang.chat.out;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.hapbang.testsupport.IntegrationTest;

/**
 * 재처리 감사·누적 횟수 마이그레이션의 기존 데이터 처리 (계약 tables-and-migrations "운영 재처리 감사와 누적 횟수").
 * 같은 PostgreSQL 컨테이너에 빈 데이터베이스를 따로 만들어 {@code V202610291220}까지 올리고, 이미 시도·재적재한 outbox와
 * 체크포인트를 넣은 뒤 {@code V202610291230}을 적용한다. 테스트 공용 DB는 건드리지 않는다.
 */
@IntegrationTest
class ChatDeliveryReprocessAuditMigrationTest {

    private static final String BEFORE_RECIPIENT_SET = "202610291210";
    private static final String BEFORE_REPROCESS_AUDIT = "202610291220";

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcClient adminClient;

    private String database;
    private DataSource dataSource;
    private JdbcClient jdbc;

    @BeforeEach
    void createDatabase() {
        database = "chat_migration_" + UUID.randomUUID().toString().replace("-", "");
        adminClient.sql("create database " + database).update();
        String url = environment.getRequiredProperty("spring.datasource.url")
                .replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
        dataSource = new DriverManagerDataSource(url, environment.getRequiredProperty("spring.datasource.username"),
                environment.getRequiredProperty("spring.datasource.password"));
        jdbc = JdbcClient.create(dataSource);
    }

    @AfterEach
    void dropDatabase() {
        adminClient.sql("drop database if exists " + database + " with (force)").update();
    }

    @Test
    void 기존_outbox와_체크포인트의_누적_횟수는_지금까지의_횟수로_채우고_감사_테이블은_비어_있다() {
        migrate(BEFORE_RECIPIENT_SET);
        long roomId = jdbc.sql("""
                        insert into chats.chat_room (room_type, status, direct_key, last_sequence, created_at, updated_at)
                        values ('DIRECT', 'ACTIVE', '1:2', 1, now(), now())
                        returning id
                        """).query(Long.class).single();
        for (long userId : new long[] {1, 2}) {
            jdbc.sql("""
                            insert into chats.chat_room_member (chat_room_id, user_id, joined_at, visible_from_sequence,
                                    participation_version, last_read_sequence)
                            values (:roomId, :userId, now(), 1, 1, 0)
                            """)
                    .param("roomId", roomId)
                    .param("userId", userId)
                    .update();
        }
        migrate(BEFORE_REPROCESS_AUDIT);
        long messageId = jdbc.sql("""
                        insert into chats.chat_message (chat_room_id, room_sequence, sender_user_id, message_type,
                                content, sent_at)
                        values (:roomId, 1, 1, 'TEXT', '안녕하세요', now())
                        returning id
                        """).param("roomId", roomId).query(Long.class).single();
        long setId = jdbc.sql("select current_recipient_set_id from chats.chat_room where id = :roomId")
                .param("roomId", roomId).query(Long.class).single();
        UUID deadDelivery = deliveryOutbox(roomId, messageId, setId, "DEAD", 5, 0);
        UUID enqueuedDelivery = deliveryOutbox(roomId, messageId, setId, "ENQUEUED", 1, 2);
        UUID control = controlOutbox("ENQUEUED", 3, 1);
        checkpoint("chat-delivery-v1", deadDelivery, "PENDING", 0);
        checkpoint("chat-delivery-v1", enqueuedDelivery, "DEAD", 3);
        checkpoint("chat-control-v1", control, "PUBLISHED", 2);

        migrate(null);

        assertThat(totals("chat_outbox", "delivery_id", deadDelivery)).containsEntry("attempt_count", 5)
                .containsEntry("total_attempt_count", 5).containsEntry("requeue_count", 0)
                .containsEntry("total_requeue_count", 0);
        assertThat(totals("chat_outbox", "delivery_id", enqueuedDelivery)).containsEntry("total_attempt_count", 1)
                .containsEntry("total_requeue_count", 2);
        assertThat(totals("chat_control_outbox", "control_id", control)).containsEntry("total_attempt_count", 3)
                .containsEntry("total_requeue_count", 1);
        assertThat(jdbc.sql("""
                        select job_id, attempt_count, total_attempt_count from chats.chat_publish_checkpoint
                        """).query((rs, rowNum) -> rs.getInt("attempt_count") == rs.getInt("total_attempt_count"))
                .list()).hasSize(3).containsOnly(true);
        assertThat(jdbc.sql("select count(*) from chats.chat_delivery_reprocess_audit").query(Long.class).single())
                .isZero();
        assertThat(jdbc.sql("select version from flyway_schema_history where version = '202610291230' and success")
                .query(String.class).list()).hasSize(1);
    }

    @Test
    void 누적_횟수_컬럼은_NOT_NULL_기본값_0이라_누적_컬럼_없이_넣는_제어_기록도_0으로_시작한다() {
        migrate(null);

        List<String> columns = jdbc.sql("""
                        select table_name || '.' || column_name
                        from information_schema.columns
                        where table_schema = 'chats' and column_name in ('total_attempt_count', 'total_requeue_count')
                          and is_nullable = 'NO' and column_default = '0'
                        order by 1
                        """).query(String.class).list();
        assertThat(columns).containsExactly(
                "chat_control_outbox.total_attempt_count", "chat_control_outbox.total_requeue_count",
                "chat_outbox.total_attempt_count", "chat_outbox.total_requeue_count",
                "chat_publish_checkpoint.total_attempt_count");

        // JdbcChatConnectionControl과 같은 컬럼만 넣는다
        UUID control = UUID.randomUUID();
        jdbc.sql("""
                        insert into chats.chat_control_outbox (control_id, control_type, user_id, member_version, reason,
                                schema_version, status, attempt_count, next_attempt_at, requeue_count, created_at,
                                updated_at)
                        values (:id, 'RECHECK_USER', 7, null, 'MEMBER_CHANGED', 1, 'PENDING', 0, now(), 0, now(), now())
                        """).param("id", control).update();
        jdbc.sql("""
                        insert into chats.chat_publish_checkpoint (consumer_group, job_id, status, generation,
                                attempt_count, created_at, updated_at)
                        values ('chat-control-v1', :id, 'PENDING', 0, 0, now(), now())
                        """).param("id", control).update();
        assertThat(totals("chat_control_outbox", "control_id", control)).containsEntry("total_attempt_count", 0)
                .containsEntry("total_requeue_count", 0);
        assertThat(jdbc.sql("select total_attempt_count from chats.chat_publish_checkpoint where job_id = :id")
                .param("id", control).query(Integer.class).single()).isZero();
    }

    private void migrate(String target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .outOfOrder(true);
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    private UUID deliveryOutbox(long roomId, long messageId, long setId, String status, int attempts, int requeues) {
        UUID deliveryId = UUID.randomUUID();
        jdbc.sql("""
                        insert into chats.chat_outbox (delivery_id, delivery_type, message_id, chat_room_id,
                                room_sequence, recipient_set_id, notify_rule, schema_version, status, attempt_count,
                                next_attempt_at, stream_entry_id, enqueued_at, requeue_count, last_error, created_at,
                                updated_at)
                        values (:id, 'MESSAGE', :messageId, :roomId, 1, :setId, 'RECIPIENTS_EXCEPT_SENDER', 1, :status,
                                :attempts, now(), '1-0', now(), :requeues, '이전 오류', now(), now())
                        """)
                .param("id", deliveryId)
                .param("messageId", messageId)
                .param("roomId", roomId)
                .param("setId", setId)
                .param("status", status)
                .param("attempts", attempts)
                .param("requeues", requeues)
                .update();
        return deliveryId;
    }

    private UUID controlOutbox(String status, int attempts, int requeues) {
        UUID controlId = UUID.randomUUID();
        jdbc.sql("""
                        insert into chats.chat_control_outbox (control_id, control_type, user_id, member_version, reason,
                                schema_version, status, attempt_count, next_attempt_at, stream_entry_id, enqueued_at,
                                requeue_count, created_at, updated_at)
                        values (:id, 'RECHECK_USER', 1, 1, 'MEMBER_CHANGED', 1, :status, :attempts, now(), '2-0', now(),
                                :requeues, now(), now())
                        """)
                .param("id", controlId)
                .param("status", status)
                .param("attempts", attempts)
                .param("requeues", requeues)
                .update();
        return controlId;
    }

    private void checkpoint(String group, UUID jobId, String status, int attempts) {
        jdbc.sql("""
                        insert into chats.chat_publish_checkpoint (consumer_group, job_id, status, generation,
                                attempt_count, created_at, updated_at)
                        values (:group, :jobId, :status, 0, :attempts, now(), now())
                        """)
                .param("group", group)
                .param("jobId", jobId)
                .param("status", status)
                .param("attempts", attempts)
                .update();
    }

    private Map<String, Object> totals(String table, String jobColumn, UUID jobId) {
        return jdbc.sql("""
                        select attempt_count, total_attempt_count, requeue_count, total_requeue_count
                        from chats.%s where %s = :jobId
                        """.formatted(table, jobColumn))
                .param("jobId", jobId)
                .query()
                .singleRow();
    }
}
