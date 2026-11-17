package com.hapbang.coupon.out;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import com.hapbang.coupon.domain.CouponStatus;
import com.hapbang.coupon.domain.QCoupon;
import com.hapbang.coupon.domain.QCouponIssueRequest;
import com.hapbang.coupon.domain.RecoveryIssueResult;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQueryFactory;

import jakarta.persistence.EntityManager;

class RecoveryLookupRepositoryCustomImpl implements RecoveryLookupRepositoryCustom {

    private final JPAQueryFactory query;

    RecoveryLookupRepositoryCustomImpl(EntityManager entityManager) {
        this.query = CouponQuerydslSupport.queryFactory(entityManager);
    }

    @Override
    public List<RecoveryIssueResult> findResults(Long couponId) {
        QCouponIssueRequest r = new QCouponIssueRequest("r");
        return query.select(Projections.constructor(RecoveryIssueResult.class, r.issueRequestId, r.memberId,
                        r.result, r.acceptedAt, r.epoch))
                .from(r)
                .where(r.couponId.eq(couponId))
                .orderBy(r.id.asc())
                .fetch();
    }

    @Override
    public List<String> findExistingRequestIds(Long couponId, Collection<String> issueRequestIds) {
        QCouponIssueRequest r = new QCouponIssueRequest("r");
        return query.select(r.issueRequestId).from(r)
                .where(r.couponId.eq(couponId), r.issueRequestId.in(issueRequestIds))
                .fetch();
    }

    @Override
    public List<Long> findRedisReadyCouponIds(Collection<CouponStatus> statuses, Instant endedAfter) {
        QCoupon c = new QCoupon("c");
        return query.select(c.id).from(c)
                .where(c.redisReady.eq(Expressions.TRUE), c.status.in(statuses), c.issueEndAt.gt(endedAfter))
                .orderBy(c.id.asc())
                .fetch();
    }
}
