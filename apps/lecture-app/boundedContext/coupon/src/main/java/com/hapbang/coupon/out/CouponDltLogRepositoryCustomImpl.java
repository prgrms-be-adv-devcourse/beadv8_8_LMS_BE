package com.hapbang.coupon.out;

import static com.hapbang.coupon.out.CouponQuerydslSupport.literal;

import java.util.List;

import com.hapbang.coupon.app.port.CouponDltLogRepositoryCustom;
import com.hapbang.coupon.domain.CouponDltStatus;
import com.hapbang.coupon.domain.QCouponDltLog;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;

import jakarta.persistence.EntityManager;

class CouponDltLogRepositoryCustomImpl implements CouponDltLogRepositoryCustom {

    private final JPAQueryFactory query;

    CouponDltLogRepositoryCustomImpl(EntityManager entityManager) {
        this.query = CouponQuerydslSupport.queryFactory(entityManager);
    }

    @Override
    public List<String> findUnresolvedRequestIds(Long couponId) {
        QCouponDltLog l = new QCouponDltLog("l");
        return query.select(l.issueRequestId).from(l)
                .where(l.couponId.eq(couponId), unresolved(l), l.issueRequestId.isNotNull())
                .fetch();
    }

    @Override
    public long countUnresolved() {
        QCouponDltLog l = new QCouponDltLog("l");
        Long count = query.select(l.count()).from(l).where(unresolved(l)).fetchOne();
        return count == null ? 0 : count;
    }

    /** 아직 처리되지 않음: PENDING과 REPLAYING(재발행 선점 중) */
    private static BooleanExpression unresolved(QCouponDltLog l) {
        return l.status.in(literal(CouponDltStatus.PENDING), literal(CouponDltStatus.REPLAYING));
    }
}
