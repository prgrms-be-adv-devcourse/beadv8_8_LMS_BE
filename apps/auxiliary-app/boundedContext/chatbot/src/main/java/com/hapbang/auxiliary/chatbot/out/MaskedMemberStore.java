package com.hapbang.auxiliary.chatbot.out;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/**
 * 개인정보를 마스킹한 회원 {@code chatbot.masked_member}과 그 회원 데이터 삭제 (#717). 트랜잭션은 부르는 쪽이 연다.
 * <p>
 * 회원 데이터를 쓰는 적용기와 마스킹은 {@link #lock}(트랜잭션 advisory lock)으로 회원마다 직렬화한다. 마스킹 직후 커밋 전의 행을
 * 다른 적용기가 다시 넣지 못하게 하려는 것이다.
 * <p>
 * 지울 사본은 {@link OwnedCopy} 한 곳에 적는다. 회원 한 명 삭제({@link #purge})와 회차 끝 정리({@link #purgeAll})가 같은 목록을 쓴다.
 */
@Repository
@RequiredArgsConstructor
public class MaskedMemberStore {

    /**
     * 회원 락의 첫 번째 키. 실행 락({@code JobLock})은 bigint 키 하나를, 회원 락은 int 키 두 개를 써서 PostgreSQL에서 서로 다른 키 공간이다.
     * 두 번째 키는 회원 ID의 해시라 다른 회원과 겹칠 수 있지만, 겹치면 잠깐 기다릴 뿐이다.
     */
    static final int MEMBER_LOCK_CLASS = 717;

    // 분야·자기소개 비우기. 대상 조건 뒤에 붙인다
    private static final String CLEAR_PROFILE = "UPDATE chatbot.member_copy SET categories = '[]', bio = NULL "
            + "WHERE (categories <> '[]' OR bio IS NOT NULL) AND id ";

    // 싱크 회차 끝의 멱등 정리. 마스킹 회원의 행이 어떤 경로로든 남았으면 지운다
    private static final String PURGE_ALL = "WITH masked AS (SELECT user_id FROM chatbot.masked_member),\n"
            + Arrays.stream(OwnedCopy.values())
            .map(copy -> copy.name() + " AS (" + copy.delete("IN (SELECT user_id FROM masked)") + " RETURNING 1),\n")
            .collect(Collectors.joining())
            + "profile AS (" + CLEAR_PROFILE + "IN (SELECT user_id FROM masked) RETURNING 1)\n"
            + "SELECT " + Arrays.stream(OwnedCopy.values())
            .map(copy -> "(SELECT count(*) FROM " + copy.name() + ") + ")
            .collect(Collectors.joining())
            + "(SELECT count(*) FROM profile)";

    private final NamedParameterJdbcTemplate jdbc;

    /** 이 회원의 트랜잭션 락. 트랜잭션이 끝나면 풀린다 */
    public void lock(long userId) {
        jdbc.query("SELECT pg_advisory_xact_lock(:lockClass, :lockKey)",
                new MapSqlParameterSource().addValue("lockClass", MEMBER_LOCK_CLASS)
                        .addValue("lockKey", Long.hashCode(userId)),
                rs -> null);
    }

    public boolean isMasked(long userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM chatbot.masked_member WHERE user_id = :userId)",
                new MapSqlParameterSource("userId", userId), Boolean.class));
    }

    /** 마스킹 회원으로 넣는다. 처음 넣었으면 true(이미 있으면 그대로 두고 false) */
    public boolean mask(long userId, LocalDateTime maskedAt) {
        return jdbc.update("""
                INSERT INTO chatbot.masked_member (user_id, masked_at) VALUES (:userId, :maskedAt)
                ON CONFLICT (user_id) DO NOTHING
                """, new MapSqlParameterSource().addValue("userId", userId).addValue("maskedAt", maskedAt)) == 1;
    }

    /** 이 회원의 {@link OwnedCopy} 행을 지우고 분야·자기소개를 비운다 */
    public Purged purge(long userId) {
        MapSqlParameterSource params = new MapSqlParameterSource("userId", userId);
        Map<OwnedCopy, Integer> deleted = new EnumMap<>(OwnedCopy.class);
        for (OwnedCopy copy : OwnedCopy.values()) {
            deleted.put(copy, jdbc.update(copy.delete("= :userId"), params));
        }
        return new Purged(deleted, jdbc.update(CLEAR_PROFILE + "= :userId", params) == 1);
    }

    /** 모든 마스킹 회원에 대해 {@link #purge}를 다시 한다. 지우거나 비운 행 수 */
    public long purgeAll() {
        Long count = jdbc.queryForObject(PURGE_ALL, new MapSqlParameterSource(), Long.class);
        return count == null ? 0 : count;
    }

    /** 마스킹하면 그 회원의 행을 지우는 사본. 사본을 더하면 여기에만 적는다 */
    public enum OwnedCopy {
        PURCHASE("lecture_purchase_copy", "buyer_user_id"),
        CART_ITEM("cart_item_copy", "user_id"),
        LECTURE_REVIEW("lecture_review_copy", "reviewer_user_id"),
        RECEIVED_APPEARANCE_REVIEW("appearance_review_copy", "reviewee_user_id");

        private final String table;
        private final String userColumn;

        OwnedCopy(String table, String userColumn) {
            this.table = table;
            this.userColumn = userColumn;
        }

        // 회원 열 조건(예: "= :userId")에 맞는 행을 지우는 문장
        private String delete(String userCondition) {
            return "DELETE FROM chatbot." + table + " WHERE " + userColumn + " " + userCondition;
        }
    }

    /**
     * 마스킹으로 지운 결과.
     *
     * @param deleted        사본별로 지운 행 수
     * @param profileCleared 분야·자기소개를 비웠는지(이미 비어 있었으면 false)
     */
    public record Purged(Map<OwnedCopy, Integer> deleted, boolean profileCleared) {

        public Purged {
            Map<OwnedCopy, Integer> ordered = new EnumMap<>(OwnedCopy.class);
            ordered.putAll(deleted);
            deleted = Collections.unmodifiableMap(ordered);
        }
    }
}
