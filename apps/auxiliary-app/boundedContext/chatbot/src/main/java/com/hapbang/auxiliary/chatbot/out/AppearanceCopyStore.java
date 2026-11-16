package com.hapbang.auxiliary.chatbot.out;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.hapbang.auxiliary.chatbot.domain.AppearanceCopy;
import com.hapbang.auxiliary.chatbot.domain.ApplySource;
import com.hapbang.auxiliary.chatbot.domain.CopyOutcome;
import com.hapbang.auxiliary.chatbot.domain.CopyWrite;

import lombok.RequiredArgsConstructor;

/**
 * 출연 사본 {@code chatbot.appearance_copy} (#716).
 * <p>
 * 반영은 upsert 한 문장이다. 이벤트와 싱크가 같은 행을 동시에 바꿔도, 값은 받은 version이 크거나(같은데 해시가 다르면) 바뀌고
 * 아니면 그대로라 큰 version을 잃지 않는다.
 * <ul>
 *     <li>이벤트: 값을 바꾸지 않을 때(우리가 새것·같음)는 행을 건드리지 않는다. 늦게 온 옛 이벤트가 받은 시각을 고치거나
 *     원본 없음 표시를 풀지 않는다.</li>
 *     <li>싱크: 값을 바꾸지 않아도 원본에 있다는 표시(원본 없음 해제, 본 회차, 받은 시각)는 남긴다. 남기지 않으면 회차 끝의
 *     원본 없음 표시가 멀쩡한 행을 잡는다.</li>
 * </ul>
 * 반영 전 값은 PostgreSQL 18 {@code RETURNING old.*}로 같은 문장에서 읽어 결과({@link CopyOutcome})를 정한다. 이벤트를 건드리지
 * 않아 돌려받은 행이 없으면 그때 우리 행을 읽어 정한다. 이 읽기는 다른 문장이라 그 사이 다른 반영이 끼면 결과가 어긋날 수 있다.
 * 결과는 이벤트 로그에만 쓰고(싱크는 늘 행을 돌려받는다) 사본 값은 upsert가 지키므로 받아들인다.
 * <p>
 * SQL은 다른 사본과 같은 {@link CopyTable}이 만든다.
 */
@Repository
@RequiredArgsConstructor
public class AppearanceCopyStore {

    // version 열은 NOT NULL이라 CopyTable의 version이 빈 경우 분기는 타지 않는다
    static final CopyTable TABLE = new CopyTable("appearance_copy", "id",
            List.of("host_id", "guest_id", "result", "scheduled_at"), Set.of(), true);

    private final NamedParameterJdbcTemplate jdbc;

    /** 받은 값을 반영한다 */
    public CopyWrite save(AppearanceCopy copy, ApplySource source, LocalDateTime now) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", copy.id())
                .addValue("host_id", copy.hostId())
                .addValue("guest_id", copy.guestId())
                .addValue("result", copy.result().name())
                .addValue("scheduled_at", copy.scheduledAt());
        return TABLE.save(jdbc, params, copy.version(), copy.hash(), source.runId(), now);
    }

    /** 끝까지 성공한 회차의 끝에 원본에 없던 행을 표시한다. 새로 표시한 행을 돌려준다 */
    public List<MissingCopy> markMissing(long runId, LocalDateTime startedAt) {
        return TABLE.markMissing(jdbc, runId, startedAt);
    }
}
