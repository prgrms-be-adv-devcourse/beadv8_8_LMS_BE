package com.hapbang.lecture.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.hapbang.testsupport.IntegrationTest;

/**
 * 반환 지연 회차 칸(#818, D83, {@code V202611101029})은 NOT NULL·기본값 0이다. PostgreSQL은 기본값이 있는 칸을 더하면 기존 행을 그
 * 기본값으로 채우므로, 배포 전 주문 항목은 0에서 시작한다.
 * <p>
 * 빈 데이터베이스를 따로 만들어 직전 버전부터 올리는 방식(chat-app의 마이그레이션 테스트)은 강의 전체 테스트에서 캐시된 컨텍스트들이
 * PostgreSQL 연결을 다 써(최대 100) 새 연결을 얻지 못해 실패했다. 그래서 테스트 공용 DB(마이그레이션 적용됨)의 칸 정의와,
 * 값을 넣지 않은 행으로 확인한다.
 */
@IntegrationTest
class OrderItemReturnDelayCountColumnTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 반환_지연_횟수_칸은_NOT_NULL_기본값_0이고_값을_넣지_않은_주문_항목은_0이다() {
        Map<String, Object> column = jdbcTemplate.queryForMap("""
                select is_nullable, column_default, data_type from information_schema.columns
                where table_schema = 'lectures' and table_name = 'order_item' and column_name = 'return_delay_count'
                """);
        jdbcTemplate.update("""
                insert into lectures.lecture_order (id, buyer_user_id, total_amount_hp, status, ordered_at)
                values (900001, 2, 10000, 'PAID', now())
                """);
        // 칸을 모르는 옛 코드·SQL이 넣은 행과 같다
        jdbcTemplate.update("""
                insert into lectures.order_item (id, order_id, lecture_id, seller_user_id, buyer_user_id,
                                                 lecture_title_snapshot, price_hp, status, objection_status)
                values (900010, 900001, 100, 1, 2, '강의', 10000, 'RETURN_PENDING', 'ACCEPTED')
                """);

        assertThat(column).containsEntry("is_nullable", "NO").containsEntry("column_default", "0")
                .containsEntry("data_type", "integer");
        assertThat(jdbcTemplate.queryForObject("select return_delay_count from lectures.order_item where id = 900010",
                Integer.class)).isZero();
    }
}
