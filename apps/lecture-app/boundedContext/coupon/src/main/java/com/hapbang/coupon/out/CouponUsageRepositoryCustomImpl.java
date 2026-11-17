package com.hapbang.coupon.out;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;

import com.hapbang.coupon.app.port.CouponUsageRepositoryCustom;
import com.hapbang.coupon.app.port.UsedCoupon;
import com.hapbang.coupon.domain.CouponUsageStatus;
import com.hapbang.coupon.domain.QCoupon;
import com.hapbang.coupon.domain.QCouponUsage;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;

import jakarta.persistence.EntityManager;

class CouponUsageRepositoryCustomImpl implements CouponUsageRepositoryCustom {

    private final JPAQueryFactory query;

    CouponUsageRepositoryCustomImpl(EntityManager entityManager) {
        this.query = CouponQuerydslSupport.queryFactory(entityManager);
    }

    /**
     * 내용과 전체 수를 따로 읽는다. 전체 수는 Spring Data 페이지 조회와 같이 내용만으로 알 수 있으면(첫 페이지가 덜 찼거나 마지막 페이지)
     * 세지 않는다. 정렬은 {@code pageable}이 아닌 예약 최신순으로 고정한다.
     */
    @Override
    public Page<UsedCoupon> findMyUsages(long memberId, CouponUsageStatus status, Pageable pageable) {
        QCouponUsage u = new QCouponUsage("u");
        QCoupon c = new QCoupon("c");
        BooleanExpression statusMatches = status == null ? null : u.status.eq(status);
        JPAQuery<UsedCoupon> content = query.select(Projections.constructor(UsedCoupon.class, u, c))
                .from(u)
                .join(c).on(c.id.eq(u.couponId))
                .where(u.memberId.eq(memberId), statusMatches)
                .orderBy(u.reservedAt.desc(), u.id.desc());
        if (pageable.isPaged()) {
            // 첫 페이지는 offset을 걸지 않는다(Spring Data 페이지 조회와 같은 SQL)
            if (pageable.getOffset() > 0) {
                content.offset(pageable.getOffset());
            }
            content.limit(pageable.getPageSize());
        }
        List<UsedCoupon> usages = content.fetch();
        return PageableExecutionUtils.getPage(usages, pageable, () -> {
            Long total = query.select(u.count()).from(u).where(u.memberId.eq(memberId), statusMatches).fetchOne();
            return total == null ? 0 : total;
        });
    }
}
