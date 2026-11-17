package com.hapbang.coupon.out;

import static com.hapbang.coupon.out.CouponQuerydslSupport.literal;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;

import com.hapbang.coupon.app.port.MemberCouponBudgetState;
import com.hapbang.coupon.app.port.MemberCouponExpiryTarget;
import com.hapbang.coupon.app.port.MemberCouponRepositoryCustom;
import com.hapbang.coupon.app.port.MemberCouponState;
import com.hapbang.coupon.app.port.OwnedCoupon;
import com.hapbang.coupon.domain.CouponTargetType;
import com.hapbang.coupon.domain.MemberCouponStatus;
import com.hapbang.coupon.domain.QCoupon;
import com.hapbang.coupon.domain.QMemberCoupon;
import com.querydsl.core.types.ConstructorExpression;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;

import jakarta.persistence.EntityManager;

class MemberCouponRepositoryCustomImpl implements MemberCouponRepositoryCustom {

    private final JPAQueryFactory query;

    MemberCouponRepositoryCustomImpl(EntityManager entityManager) {
        this.query = CouponQuerydslSupport.queryFactory(entityManager);
    }

    @Override
    public List<Long> findCouponIdsOwnedBy(long memberId, Collection<Long> couponIds) {
        QMemberCoupon mc = new QMemberCoupon("mc");
        return query.select(mc.couponId).from(mc)
                .where(mc.memberId.eq(memberId), mc.couponId.in(couponIds))
                .fetch();
    }

    @Override
    public List<OwnedCoupon> findMyCoupons(long memberId, MemberCouponStatus status) {
        QMemberCoupon mc = new QMemberCoupon("mc");
        QCoupon c = new QCoupon("c");
        return ownedCoupons(mc, c)
                .where(mc.memberId.eq(memberId), status == null ? null : mc.status.eq(status))
                .orderBy(mc.issuedAt.desc(), mc.id.desc())
                .fetch();
    }

    @Override
    public List<OwnedCoupon> findUsableFor(long memberId, long lectureId, Instant now) {
        QMemberCoupon mc = new QMemberCoupon("mc");
        QCoupon c = new QCoupon("c");
        return ownedCoupons(mc, c)
                .where(mc.memberId.eq(memberId),
                        mc.status.eq(literal(MemberCouponStatus.AVAILABLE)),
                        mc.expiresAt.gt(now),
                        c.targetType.eq(literal(CouponTargetType.LECTURE)).and(c.lectureId.eq(lectureId))
                                .or(c.targetType.eq(literal(CouponTargetType.ANY_LECTURE))))
                .orderBy(mc.expiresAt.asc(), mc.id.asc())
                .fetch();
    }

    @Override
    public List<OwnedCoupon> findOwnedByIds(Collection<Long> ids) {
        QMemberCoupon mc = new QMemberCoupon("mc");
        QCoupon c = new QCoupon("c");
        return ownedCoupons(mc, c).where(mc.id.in(ids)).fetch();
    }

    @Override
    public Optional<MemberCouponState> findStateById(long id) {
        QMemberCoupon mc = new QMemberCoupon("mc");
        return Optional.ofNullable(query.select(Projections.constructor(MemberCouponState.class, mc.id, mc.memberId,
                        mc.status, mc.generation, mc.expiresAt, mc.currentOrderItemId))
                .from(mc)
                .where(mc.id.eq(id))
                .fetchOne());
    }

    @Override
    public List<MemberCouponExpiryTarget> findExpiredCouponTargets(Instant now, Instant afterExpiresAt, long afterId,
                                                                   Limit limit) {
        QMemberCoupon mc = new QMemberCoupon("mc");
        // 첫 페이지는 커서 조건을 넣지 않는다. 같은 만료 시각의 ID도 커서에 포함해 실패한 행을 건너뛰면서 기한 순서를 유지한다
        BooleanExpression after = afterExpiresAt == null ? null
                : mc.expiresAt.gt(afterExpiresAt).or(mc.expiresAt.eq(afterExpiresAt).and(mc.id.gt(afterId)));
        return CouponQuerydslSupport.fetch(query.select(expiryTarget(mc)).from(mc)
                .where(mc.status.eq(literal(MemberCouponStatus.AVAILABLE)), mc.expiresAt.loe(now), after)
                .orderBy(mc.expiresAt.asc(), mc.id.asc()), limit);
    }

    @Override
    public Optional<MemberCouponBudgetState> findBudgetState(long id) {
        QMemberCoupon mc = new QMemberCoupon("mc");
        return Optional.ofNullable(query.select(Projections.constructor(MemberCouponBudgetState.class, mc.id,
                        mc.couponId, mc.status, mc.generation, mc.expiresAt, mc.budgetReleasePlannedHp,
                        mc.budgetReleaseCheckedStatus))
                .from(mc)
                .where(mc.id.eq(id))
                .fetchOne());
    }

    @Override
    public List<MemberCouponExpiryTarget> findExpiredPlatformBudgetTargets(long couponId, Instant now,
            Instant afterExpiresAt, long afterId, Limit limit) {
        QMemberCoupon mc = new QMemberCoupon("mc");
        // 후속 페이지는 (expiresAt, id) 행 값 비교로 부분 인덱스 (coupon_id, expires_at, id)의 범위를 그대로 이어 읽는다.
        // QueryDSL에 행 값 비교가 없어 템플릿으로 쓴다(기존 JPQL과 같은 식)
        Predicate after = afterExpiresAt == null ? null
                : Expressions.booleanTemplate("({0}, {1}) > ({2}, {3})", mc.expiresAt, mc.id, afterExpiresAt, afterId);
        return CouponQuerydslSupport.fetch(query.select(expiryTarget(mc)).from(mc)
                .where(mc.couponId.eq(couponId), mc.expiresAt.loe(now), after,
                        // 부분 인덱스 idx_member_coupon_budget_unchecked의 조건과 같은 식이다
                        mc.status.in(literal(MemberCouponStatus.USED), literal(MemberCouponStatus.EXPIRED)),
                        mc.budgetReleaseCheckedStatus.isNull().or(mc.budgetReleaseCheckedStatus.ne(mc.status)))
                .orderBy(mc.expiresAt.asc(), mc.id.asc()), limit);
    }

    /** 발급 쿠폰과 그 쿠폰 정책. 정책 조인은 같은 쿠폰 스키마 안의 연관 없는 엔티티 조인이다 */
    private JPAQuery<OwnedCoupon> ownedCoupons(QMemberCoupon mc, QCoupon c) {
        return query.select(Projections.constructor(OwnedCoupon.class, mc, c))
                .from(mc)
                .join(c).on(c.id.eq(mc.couponId));
    }

    private static ConstructorExpression<MemberCouponExpiryTarget> expiryTarget(QMemberCoupon mc) {
        return Projections.constructor(MemberCouponExpiryTarget.class, mc.id, mc.expiresAt);
    }
}
