package com.hapbang.auxiliary.chatbot.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.chatbot.support.ChatbotIntegrationTestBase;

/**
 * 엔티티 없이 JDBC로 다루는 chatbot 테이블의 컬럼(이름·타입)을 고정한다(#835). 이 테이블들은 {@code ddl-auto: validate} 검사 밖이라
 * Flyway가 컬럼을 바꿔도 앱은 뜨고 Store의 SQL만 실행 때 깨진다. 이 테스트가 깨지면 Store의 SQL을 같이 고치고 아래 표를 맞춘다.
 * 표는 Store가 쓰는 컬럼이다: 사본은 {@link CopyTable}(키·데이터 컬럼 + version·dto_hash·원본 없음 표시·synced_at),
 * 색인 요청은 {@link IndexTaskStore}, 마스킹 회원은 {@link MaskedMemberStore}.
 */
class JdbcTableColumnsTest extends ChatbotIntegrationTestBase {

    // 엔티티로 다루는 테이블(ddl-auto: validate가 검사한다)
    private static final Set<String> ENTITY_TABLES = Set.of("faq", "sync_run", "sync_mismatch");

    private static final String BIGINT = "bigint";
    private static final String INTEGER = "integer";
    private static final String BOOLEAN = "boolean";
    private static final String TEXT = "text";
    private static final String JSONB = "jsonb";
    private static final String TIMESTAMP = "timestamp without time zone";
    private static final String HASH = "character(64)";

    private static String varchar(int length) {
        return "character varying(" + length + ")";
    }

    private static Map<String, Map<String, String>> expected() {
        Map<String, Map<String, String>> tables = new LinkedHashMap<>();
        tables.put("appearance_copy", columns(
                "id", BIGINT,
                "host_id", BIGINT,
                "guest_id", BIGINT,
                "result", varchar(20),
                "scheduled_at", TIMESTAMP,
                "version", BIGINT,
                "dto_hash", HASH,
                "missing_in_source", BOOLEAN,
                "seen_run_id", BIGINT,
                "synced_at", TIMESTAMP));
        tables.put("appearance_review_copy", columns(
                "review_id", BIGINT,
                "appearance_id", BIGINT,
                "reviewee_user_id", BIGINT,
                "rating", INTEGER,
                "dto_hash", HASH,
                "missing_in_source", BOOLEAN,
                "seen_run_id", BIGINT,
                "synced_at", TIMESTAMP));
        tables.put("member_copy", columns(
                "id", BIGINT,
                "role", varchar(30),
                "status", varchar(20),
                "categories", JSONB,
                "bio", TEXT,
                "version", BIGINT,
                "dto_hash", HASH,
                "synced_at", TIMESTAMP));
        tables.put("lecture_copy", columns(
                "id", BIGINT,
                "seller_user_id", BIGINT,
                "title", TEXT,
                "description", TEXT,
                "content_titles", JSONB,
                "on_sale", BOOLEAN,
                "deleted", BOOLEAN,
                "version", BIGINT,
                "dto_hash", HASH,
                "missing_in_source", BOOLEAN,
                "seen_run_id", BIGINT,
                "synced_at", TIMESTAMP));
        tables.put("lecture_purchase_copy", columns(
                "order_item_id", BIGINT,
                "buyer_user_id", BIGINT,
                "lecture_id", BIGINT,
                "result", varchar(20),
                "ordered_at", TIMESTAMP,
                "purchased_at", TIMESTAMP,
                "version", BIGINT,
                "dto_hash", HASH,
                "missing_in_source", BOOLEAN,
                "seen_run_id", BIGINT,
                "synced_at", TIMESTAMP));
        tables.put("cart_item_copy", columns(
                "id", BIGINT,
                "user_id", BIGINT,
                "lecture_id", BIGINT,
                "deleted", BOOLEAN,
                "added_at", TIMESTAMP,
                "version", BIGINT,
                "dto_hash", HASH,
                "missing_in_source", BOOLEAN,
                "seen_run_id", BIGINT,
                "synced_at", TIMESTAMP));
        tables.put("lecture_review_copy", columns(
                "id", BIGINT,
                "lecture_id", BIGINT,
                "reviewer_user_id", BIGINT,
                "rating", INTEGER,
                "counted", BOOLEAN,
                "deleted", BOOLEAN,
                "version", BIGINT,
                "dto_hash", HASH,
                "missing_in_source", BOOLEAN,
                "seen_run_id", BIGINT,
                "synced_at", TIMESTAMP));
        tables.put("masked_member", columns(
                "user_id", BIGINT,
                "masked_at", TIMESTAMP));
        tables.put("index_task", columns(
                "id", BIGINT,
                "index_type", varchar(30),
                "doc_id", varchar(100),
                "action", varchar(10),
                "payload", JSONB,
                "attempts", INTEGER,
                "next_attempt_at", TIMESTAMP,
                "done_at", TIMESTAMP,
                "created_at", TIMESTAMP,
                // IndexTaskStore가 실패 원인을 500글자로 자른다
                "last_error", varchar(500)));
        return tables;
    }

    private static Map<String, String> columns(String... nameAndType) {
        Map<String, String> columns = new LinkedHashMap<>();
        for (int i = 0; i < nameAndType.length; i += 2) {
            columns.put(nameAndType[i], nameAndType[i + 1]);
        }
        return columns;
    }

    @Test
    @DisplayName("JDBC로 다루는 테이블의 컬럼 이름·타입이 Store의 SQL이 쓰는 것과 같다")
    void columnsMatchJdbcCode() {
        Map<String, Map<String, String>> expected = expected();

        for (Map.Entry<String, Map<String, String>> table : expected.entrySet()) {
            assertThat(actualColumns(table.getKey()))
                    .as("chatbot.%s 컬럼", table.getKey())
                    .containsExactlyInAnyOrderEntriesOf(table.getValue());
        }
    }

    @Test
    @DisplayName("chatbot schema의 테이블은 엔티티 테이블이거나 이 테스트가 컬럼을 고정한 JDBC 테이블이다")
    void everyJdbcTableIsPinned() {
        List<String> tables = jdbcTemplate.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'chatbot' AND table_type = 'BASE TABLE'
                """, String.class);
        List<String> jdbcTables = tables.stream().filter(table -> !ENTITY_TABLES.contains(table)).toList();

        assertThat(jdbcTables).containsExactlyInAnyOrderElementsOf(expected().keySet());
    }

    private Map<String, String> actualColumns(String table) {
        Map<String, String> columns = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT column_name, data_type, character_maximum_length
                FROM information_schema.columns
                WHERE table_schema = 'chatbot' AND table_name = ?
                ORDER BY ordinal_position
                """, rs -> {
            Integer length = rs.getObject("character_maximum_length", Integer.class);
            String type = rs.getString("data_type");
            columns.put(rs.getString("column_name"), length == null ? type : type + "(" + length + ")");
        }, table);
        return columns;
    }
}
