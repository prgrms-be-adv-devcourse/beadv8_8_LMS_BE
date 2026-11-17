package com.hapbang.coupon.out;

import java.util.List;

import com.hapbang.coupon.app.port.IssueResultRow;
import com.hapbang.coupon.domain.QCouponIssueRequest;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;

import jakarta.persistence.EntityManager;

class IssueResultReadRepositoryCustomImpl implements IssueResultReadRepositoryCustom {

    private final JPAQueryFactory query;

    IssueResultReadRepositoryCustomImpl(EntityManager entityManager) {
        this.query = CouponQuerydslSupport.queryFactory(entityManager);
    }

    @Override
    public List<IssueResultRow> findRowsByCouponId(long couponId) {
        QCouponIssueRequest r = new QCouponIssueRequest("r");
        return query.select(Projections.constructor(IssueResultRow.class, r.issueRequestId, r.memberId, r.result,
                        r.epoch))
                .from(r)
                .where(r.couponId.eq(couponId))
                .fetch();
    }
}
