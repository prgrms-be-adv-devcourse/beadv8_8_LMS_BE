package com.hapbang.lecture.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.hapbang.testsupport.IntegrationTest;

/**
 * 검수 회차 칸(#872, D85, {@code V202611111700})은 NOT NULL·기본값 1이다. PostgreSQL은 기본값이 있는 칸을 더하면 기존 행을 그
 * 기본값으로 채우므로, 배포 전 강의는 1회차에서 시작한다.
 * <p>
 * 빈 데이터베이스를 따로 만드는 마이그레이션 테스트는 강의 전체 테스트에서 PostgreSQL 연결이 모자라 실패하므로(#818), 테스트 공용
 * DB(마이그레이션 적용됨)의 칸 정의와 값을 넣지 않은 행으로 확인한다.
 */
@IntegrationTest
class LectureInspectionCountColumnTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 검수_회차_칸은_NOT_NULL_기본값_1이고_값을_넣지_않은_강의는_1회차다() {
        Map<String, Object> column = jdbcTemplate.queryForMap("""
                select is_nullable, column_default, data_type from information_schema.columns
                where table_schema = 'lectures' and table_name = 'lecture' and column_name = 'inspection_count'
                """);
        // 칸을 모르는 옛 코드·SQL이 넣은 행과 같다
        jdbcTemplate.update("""
                insert into lectures.lecture (id, seller_user_id, title, price_hp, status, inspection_requested_at,
                                              version, created_at, updated_at)
                values (900001, 1, '강의', 10000, 'REJECTED', now(), 0, now(), now())
                """);

        assertThat(column).containsEntry("is_nullable", "NO").containsEntry("column_default", "1")
                .containsEntry("data_type", "integer");
        assertThat(jdbcTemplate.queryForObject("select inspection_count from lectures.lecture where id = 900001",
                Integer.class)).isOne();
    }
}
