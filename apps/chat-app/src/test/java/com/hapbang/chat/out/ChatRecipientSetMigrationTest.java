package com.hapbang.chat.out;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import com.hapbang.chat.fixture.ChatNativeSql;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 불변 수신자 집합 마이그레이션의 기존 데이터 처리 (계약 tables-and-migrations "불변 수신자 집합").
 * 같은 PostgreSQL 컨테이너에 빈 데이터베이스를 따로 만들어 이 마이그레이션 직전 버전까지 올리고, 전환 전 데이터를 넣은 뒤
 * {@code V202610291220}을 적용한다. 테스트 공용 DB는 건드리지 않는다.
 */
@IntegrationTest
class ChatRecipientSetMigrationTest {

    private static final String BEFORE_RECIPIENT_SET = "202610291210";

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
    void 종료되지_않은_기존_방은_활성_참여로_버전_1_집합을_만들고_모든_방의_활성_인원을_채운다() {
        migrate(BEFORE_RECIPIENT_SET);
        long active = room("ACTIVE", "1:2", null);
        member(active, 1, 1, false, false);
        member(active, 2, 3, false, false);
        long oneLeft = room("ACTIVE", "1:3", null);
        member(oneLeft, 1, 1, false, false);
        member(oneLeft, 3, 2, true, false);
        long ended = room("ENDED", null, null);
        member(ended, 1, 1, true, false);
        member(ended, 4, 1, true, false);
        long deletedRoom = room("ACTIVE", "1:5", "now()");
        member(deletedRoom, 1, 1, false, false);
        long deletedMember = room("ACTIVE", "1:6", null);
        member(deletedMember, 1, 1, false, false);
        member(deletedMember, 6, 1, false, true);

        migrate(null);

        assertThat(setItems(active)).containsExactly("1:1", "2:3");
        assertThat(roomState(active)).containsEntry("member_count", 2).containsEntry("active_member_count", 2)
                .containsEntry("set_version", 1);
        assertThat(setItems(oneLeft)).containsExactly("1:1");
        assertThat(roomState(oneLeft)).containsEntry("member_count", 1).containsEntry("active_member_count", 1);
        assertThat(setItems(deletedMember)).containsExactly("1:1");
        assertThat(roomState(deletedMember)).containsEntry("active_member_count", 1);
        // 종료·삭제된 방에는 집합을 만들지 않는다(전송 대상으로 열지 않는다)
        assertThat(currentSetId(ended)).isNull();
        assertThat(activeMemberCount(ended)).isZero();
        assertThat(currentSetId(deletedRoom)).isNull();
        assertThat(sql.single("select count(*) from chats.chat_recipient_set", Long.class)).isEqualTo(3);
        // 기존 행을 채운 뒤에는 기본값 없이 앱이 값을 넣어야 한다
        assertThat(sql.single("""
                        select column_default is null and is_nullable = 'NO'
                        from information_schema.columns
                        where table_schema = 'chats' and table_name = 'chat_room' and column_name = 'active_member_count'
                        """, Boolean.class)).isTrue();
    }

    @Test
    void 빈_DB에서는_집합_없이_마이그레이션된다() {
        migrate(null);

        assertThat(sql.single("select count(*) from chats.chat_recipient_set", Long.class)).isZero();
        assertThat(sql.single("select max(version) from flyway_schema_history where success", String.class))
                .isGreaterThanOrEqualTo("202610291220");
    }

    private void migrate(String target) {
        database.migrate(target);
    }

    /** 값이 없는 열(direct_key·deleted_at)은 형을 알 수 없는 null 파라미터 대신 SQL에 바로 넣는다 */
    private long room(String status, String directKey, String deletedAt) {
        return sql.single("""
                        insert into chats.chat_room (room_type, status, direct_key, last_sequence, created_at, updated_at,
                                ended_at, deleted_at)
                        values ('DIRECT', :status, %s, 0, now(), now(),
                                case when :status = 'ENDED' then now() end, %s)
                        returning id
                        """.formatted(directKey == null ? "null" : "'" + directKey + "'",
                        deletedAt == null ? "null" : deletedAt), Long.class, Map.of("status", status));
    }

    private void member(long roomId, long userId, int participationVersion, boolean left, boolean deleted) {
        sql.update("""
                        insert into chats.chat_room_member (chat_room_id, user_id, joined_at, left_at,
                                visible_from_sequence, participation_version, deleted_at, last_read_sequence)
                        values (:roomId, :userId, now(), case when :left then now() end, 1, :version,
                                case when :deleted then now() end, 0)
                        """, Map.of("roomId", roomId, "userId", userId, "left", left, "version", participationVersion,
                "deleted", deleted));
    }

    private List<String> setItems(long roomId) {
        return sql.list("""
                        select m.user_id || ':' || m.participation_version
                        from chats.chat_room r
                                 join chats.chat_recipient_set_member m on m.recipient_set_id = r.current_recipient_set_id
                        where r.id = :roomId
                        order by m.user_id
                        """, String.class, Map.of("roomId", roomId));
    }

    private Map<String, Object> roomState(long roomId) {
        return sql.row("""
                        select s.set_version, s.member_count, r.active_member_count
                        from chats.chat_room r
                                 join chats.chat_recipient_set s on s.id = r.current_recipient_set_id
                        where r.id = :roomId
                        """, Map.of("roomId", roomId));
    }

    private Long currentSetId(long roomId) {
        return sql.list("select current_recipient_set_id from chats.chat_room where id = :roomId", Long.class,
                Map.of("roomId", roomId)).getFirst();
    }

    private int activeMemberCount(long roomId) {
        return sql.single("select active_member_count from chats.chat_room where id = :roomId", Integer.class,
                Map.of("roomId", roomId));
    }
}
