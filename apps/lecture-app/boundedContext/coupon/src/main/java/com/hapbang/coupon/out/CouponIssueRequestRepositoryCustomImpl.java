package com.hapbang.coupon.out;

import java.util.Collection;
import java.util.List;

import com.hapbang.coupon.app.port.CouponIssueRequestRepositoryCustom;
import com.hapbang.coupon.app.port.IssueResultRow;
import com.hapbang.coupon.domain.QCouponIssueRequest;
import com.hapbang.coupon.domain.RecoveryIssueResult;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;

import jakarta.persistence.EntityManager;

class CouponIssueRequestRepositoryCustomImpl implements CouponIssueRequestRepositoryCustom {

    private final JPAQueryFactory query;

    CouponIssueRequestRepositoryCustomImpl(EntityManager entityManager) {
        this.query = CouponQuerydslSupport.queryFactory(entityManager);
    }

    @Override
    public List<String> findExistingRequestIds(Long couponId, Collection<String> issueRequestIds) {
        QCouponIssueRequest r = new QCouponIssueRequest("r");
        return query.select(r.issueRequestId).from(r)
                .where(r.couponId.eq(couponId), r.issueRequestId.in(issueRequestIds))
                .fetch();
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
    public List<IssueResultRow> findResultRows(long couponId) {
        QCouponIssueRequest r = new QCouponIssueRequest("r");
        return query.select(Projections.constructor(IssueResultRow.class, r.issueRequestId, r.memberId, r.result,
                        r.epoch))
                .from(r)
                .where(r.couponId.eq(couponId))
                .fetch();
    }
}
