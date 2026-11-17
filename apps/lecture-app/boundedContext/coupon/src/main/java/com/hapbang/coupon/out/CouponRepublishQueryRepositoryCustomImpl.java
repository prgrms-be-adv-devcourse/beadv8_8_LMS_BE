package com.hapbang.coupon.out;

import java.util.Collection;
import java.util.List;

import com.hapbang.coupon.app.port.RepublishTargetCoupon;
import com.hapbang.coupon.domain.CouponStatus;
import com.hapbang.coupon.domain.QCoupon;
import com.hapbang.coupon.domain.QCouponIssueRequest;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQueryFactory;

import jakarta.persistence.EntityManager;

class CouponRepublishQueryRepositoryCustomImpl implements CouponRepublishQueryRepositoryCustom {

    private final JPAQueryFactory query;

    CouponRepublishQueryRepositoryCustomImpl(EntityManager entityManager) {
        this.query = CouponQuerydslSupport.queryFactory(entityManager);
    }

    @Override
    public List<RepublishTargetCoupon> findTargets(Collection<CouponStatus> statuses) {
        QCoupon c = new QCoupon("c");
        return query.select(Projections.constructor(RepublishTargetCoupon.class, c.id, c.status, c.epoch))
                .from(c)
                .where(c.redisReady.eq(Expressions.TRUE), c.status.in(statuses))
                .orderBy(c.id.asc())
                .fetch();
    }

    @Override
    public List<String> findRecordedIds(long couponId, Collection<String> issueRequestIds) {
        QCouponIssueRequest r = new QCouponIssueRequest("r");
        return query.select(r.issueRequestId).from(r)
                .where(r.couponId.eq(couponId), r.issueRequestId.in(issueRequestIds))
                .fetch();
    }
}
