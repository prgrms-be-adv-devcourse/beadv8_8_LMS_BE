package com.hapbang.auxiliary.chatbot.out;

import java.time.LocalDateTime;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.hapbang.auxiliary.chatbot.domain.IndexAction;
import com.hapbang.auxiliary.chatbot.domain.IndexType;

import lombok.RequiredArgsConstructor;

/**
 * ES 색인 요청 {@code chatbot.index_task} (#717). 데이터 변경과 같은 트랜잭션에 적고, 커밋 뒤 실행기(#718·#719)가 실행·재시도한다.
 * 트랜잭션은 부르는 쪽이 연다.
 */
@Repository
@RequiredArgsConstructor
public class IndexTaskStore {

    private final NamedParameterJdbcTemplate jdbc;

    /** 색인 요청을 적는다. 바로 실행할 수 있게 다음 시도 시각을 지금으로 둔다 */
    public void add(IndexType type, String docId, IndexAction action, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO chatbot.index_task (index_type, doc_id, action, next_attempt_at, created_at)
                VALUES (:indexType, :docId, :action, :now, :now)
                """, new MapSqlParameterSource().addValue("indexType", type.name()).addValue("docId", docId)
                .addValue("action", action.name()).addValue("now", now));
    }
}
