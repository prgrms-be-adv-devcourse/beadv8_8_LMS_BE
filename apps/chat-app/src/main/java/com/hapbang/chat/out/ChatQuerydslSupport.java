package com.hapbang.chat.out;

import java.time.LocalDateTime;

import org.springframework.data.domain.Limit;

import com.querydsl.core.types.Expression;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.EnumExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQuery;

/**
 * chat repository의 QueryDSL 구현이 함께 쓰는 식. 기존 JPQL 문장과 같은 SQL이 나오도록 맞춘다.
 */
final class ChatQuerydslSupport {

    private ChatQuerydslSupport() {
    }

    /**
     * enum 상수를 JPQL 리터럴({@code com.hapbang.chat.domain.ChatRoomStatus.ACTIVE})로 쓴다. QueryDSL은 값을 바인딩 파라미터로
     * 넘기는데, 그러면 PostgreSQL의 일반 실행 계획이 조건이 상수인 부분 인덱스(현재 단체방·조회 만료 단체방)를 쓰지 못할 수 있다.
     * 기존 {@code @Query}처럼 SQL에 상수로 남긴다.
     */
    static <E extends Enum<E>> EnumExpression<E> literal(E value) {
        Class<E> type = value.getDeclaringClass();
        return Expressions.enumTemplate(type, type.getName() + "." + value.name());
    }

    /**
     * 행 비교 {@code (시각, ID) > (커서 시각, 커서 ID)}. 두 조건을 OR로 풀지 않아야 (방, 시각, ID) 인덱스의 범위 조건이 된다.
     */
    static BooleanExpression after(Expression<LocalDateTime> time, Expression<Long> id, LocalDateTime at,
            long idAfter) {
        return Expressions.booleanTemplate("({0}, {1}) > ({2}, {3})", time, id, at, idAfter);
    }

    /** Spring Data {@link Limit} 인자와 같게 개수를 제한한다(제한 없음이면 그대로) */
    static <T> JPAQuery<T> limit(JPAQuery<T> query, Limit limit) {
        return limit.isLimited() ? query.limit(limit.max()) : query;
    }
}
