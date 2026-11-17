package com.hapbang.coupon.out;

import java.util.List;

import org.springframework.data.domain.Limit;

import com.querydsl.core.types.dsl.EnumExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.JPQLTemplates;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;

import jakarta.persistence.EntityManager;

/**
 * 쿠폰 저장소의 QueryDSL 구현({@code XxxRepositoryCustomImpl})이 함께 쓰는 것.
 * <p>
 * {@link JPAQueryFactory}는 빈으로 두지 않고 구현체마다 공유 {@link EntityManager}로 만든다. 같은 JVM에 실리는 강의 모듈이 자기
 * {@code JPAQueryFactory} 빈을 둘 수 있어 쿠폰이 이름 없는 공용 빈을 차지하지 않는다.
 */
final class CouponQuerydslSupport {

    private CouponQuerydslSupport() {
    }

    /**
     * 표준 JPQL 문법으로 쿼리를 만든다. 공급자 자동 판별은 Hibernate용 템플릿을 고르는데, 그 템플릿은 조인 조건을 Hibernate 고유 문법
     * ({@code with})으로 쓴다. 기존 JPQL과 같은 표준 문법({@code on})을 쓰도록 표준 템플릿을 명시한다(쿼리마다 공급자를 판별하지도 않는다).
     */
    static JPAQueryFactory queryFactory(EntityManager entityManager) {
        return new JPAQueryFactory(JPQLTemplates.DEFAULT, entityManager);
    }

    /**
     * enum 값을 바인딩 매개변수가 아닌 JPQL 리터럴({@code 패키지.Enum.값})로 쓴다. QueryDSL은 상수를 매개변수로 넘기는데, 상태·유형 조건이
     * 리터럴이어야 PostgreSQL이 일반 실행 계획에서도 부분 인덱스(예: {@code where status = 'PENDING'})의 조건과 맞춰 볼 수 있다. 문자열로
     * 바꾼 기존 JPQL과 같은 SQL을 만든다.
     */
    static <E extends Enum<E>> EnumExpression<E> literal(E value) {
        Class<E> type = value.getDeclaringClass();
        return Expressions.enumTemplate(type, type.getName() + "." + value.name());
    }

    /** Spring Data {@link Limit}를 쿼리 행 수 상한으로 옮긴다. 제한 없음이면 상한을 두지 않는다 */
    static <T> List<T> fetch(JPAQuery<T> query, Limit limit) {
        return limit.isLimited() ? query.limit(limit.max()).fetch() : query.fetch();
    }
}
