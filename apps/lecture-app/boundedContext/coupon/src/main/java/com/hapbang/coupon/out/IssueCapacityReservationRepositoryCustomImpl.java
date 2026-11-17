package com.hapbang.coupon.out;

import java.util.List;

import com.hapbang.coupon.domain.QIssueCapacityReservation;
import com.querydsl.jpa.impl.JPAQueryFactory;

import jakarta.persistence.EntityManager;

class IssueCapacityReservationRepositoryCustomImpl implements IssueCapacityReservationRepositoryCustom {

    private final JPAQueryFactory query;

    IssueCapacityReservationRepositoryCustomImpl(EntityManager entityManager) {
        this.query = CouponQuerydslSupport.queryFactory(entityManager);
    }

    @Override
    public List<Long> findCouponIds() {
        QIssueCapacityReservation r = new QIssueCapacityReservation("r");
        return query.select(r.couponId).from(r)
                .orderBy(r.reservedAt.asc(), r.couponId.asc())
                .fetch();
    }
}
