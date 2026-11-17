package com.hapbang.coupon.out;

import static com.hapbang.coupon.out.CouponQuerydslSupport.literal;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Limit;

import com.hapbang.coupon.app.port.CouponRepositoryCustom;
import com.hapbang.coupon.domain.BudgetWorkKind;
import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponStatus;
import com.hapbang.coupon.domain.CouponTargetType;
import com.hapbang.coupon.domain.CouponType;
import com.hapbang.coupon.domain.MemberCouponStatus;
import com.hapbang.coupon.domain.QBudgetWork;
import com.hapbang.coupon.domain.QCoupon;
import com.hapbang.coupon.domain.QMemberCoupon;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;

import jakarta.persistence.EntityManager;

class CouponRepositoryCustomImpl implements CouponRepositoryCustom {

    private final JPAQueryFactory query;

    CouponRepositoryCustomImpl(EntityManager entityManager) {
        this.query = CouponQuerydslSupport.queryFactory(entityManager);
    }

    @Override
    public List<Long> findExpiredDraftBudgetIds(Instant now, long afterId, Limit limit) {
        QCoupon c = new QCoupon("c");
        QBudgetWork w = new QBudgetWork("w");
        return CouponQuerydslSupport.fetch(query.select(c.id).from(c)
                .where(c.type.eq(literal(CouponType.PLATFORM)),
                        c.status.eq(literal(CouponStatus.DRAFT)),
                        c.issueEndAt.loe(now), c.issuedCount.eq(Expressions.ZERO),
                        c.budgetReservationId.isNotNull(), c.id.gt(afterId),
                        JPAExpressions.selectOne().from(w)
                                .where(w.couponId.eq(c.id), w.kind.eq(literal(BudgetWorkKind.RELEASE)),
                                        // 미발급분 해제 작업의 대상 표식. 기존 JPQL과 같은 리터럴 LIKE(매개변수·escape 없음)로 둔다
                                        Expressions.booleanTemplate("{0} like 'UNISSUED:%'", w.target))
                                .notExists())
                .orderBy(c.id.asc()), limit);
    }

    @Override
    public List<Long> findPlatformMemberBudgetCouponIds(Instant now, long afterId, Limit limit) {
        QCoupon c = new QCoupon("c");
        QMemberCoupon mc = new QMemberCoupon("mc");
        return CouponQuerydslSupport.fetch(query.select(c.id).from(c)
                .where(c.type.eq(literal(CouponType.PLATFORM)), c.id.gt(afterId),
                        JPAExpressions.selectOne().from(mc)
                                .where(mc.couponId.eq(c.id), mc.expiresAt.loe(now),
                                        mc.status.in(literal(MemberCouponStatus.USED),
                                                literal(MemberCouponStatus.EXPIRED)),
                                        mc.budgetReleaseCheckedStatus.isNull()
                                                .or(mc.budgetReleaseCheckedStatus.ne(mc.status)))
                                .exists())
                .orderBy(c.id.asc()), limit);
    }

    @Override
    public List<Coupon> findIssuableFor(long lectureId, Instant now, Limit limit) {
        QCoupon c = new QCoupon("c");
        return CouponQuerydslSupport.fetch(query.selectFrom(c)
                .where(c.status.eq(literal(CouponStatus.OPEN)),
                        c.issueStartAt.loe(now), c.issueEndAt.gt(now),
                        c.targetType.eq(literal(CouponTargetType.LECTURE)).and(c.lectureId.eq(lectureId))
                                .or(c.targetType.eq(literal(CouponTargetType.ANY_LECTURE))))
                .orderBy(c.createdAt.desc(), c.id.desc()), limit);
    }

    @Override
    public boolean existsPriceRaiseBlocker(long lectureId, Instant now) {
        QCoupon c = new QCoupon("c");
        QMemberCoupon mc = new QMemberCoupon("mc");
        Boolean blocked = query.select(c.count().gt(Expressions.ZERO)).from(c)
                .where(c.type.eq(literal(CouponType.PLATFORM)),
                        c.targetType.eq(literal(CouponTargetType.LECTURE)),
                        c.lectureId.eq(lectureId),
                        c.status.eq(literal(CouponStatus.OPEN)).or(JPAExpressions.selectOne().from(mc)
                                .where(mc.couponId.eq(c.id),
                                        mc.status.eq(literal(MemberCouponStatus.RESERVED))
                                                .or(mc.status.eq(literal(MemberCouponStatus.AVAILABLE))
                                                        .and(mc.expiresAt.gt(now))))
                                .exists()))
                .fetchOne();
        return Boolean.TRUE.equals(blocked);
    }
}
