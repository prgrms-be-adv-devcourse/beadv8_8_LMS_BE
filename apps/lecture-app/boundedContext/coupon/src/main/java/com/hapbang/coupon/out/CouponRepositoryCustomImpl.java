package com.hapbang.coupon.out;

import static com.hapbang.coupon.out.CouponQuerydslSupport.literal;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;

import com.hapbang.coupon.app.port.CouponRepositoryCustom;
import com.hapbang.coupon.app.port.RepublishTargetCoupon;
import com.hapbang.coupon.domain.BudgetWorkKind;
import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponStatus;
import com.hapbang.coupon.domain.CouponTargetType;
import com.hapbang.coupon.domain.CouponType;
import com.hapbang.coupon.domain.MemberCouponStatus;
import com.hapbang.coupon.domain.QBudgetWork;
import com.hapbang.coupon.domain.QCoupon;
import com.hapbang.coupon.domain.QMemberCoupon;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQuery;
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
    public List<Coupon> findIssuableFor(Long lectureId, Instant now, Limit limit) {
        QCoupon c = new QCoupon("c");
        return CouponQuerydslSupport.fetch(query.selectFrom(c)
                .where(c.status.eq(literal(CouponStatus.OPEN)),
                        c.issueStartAt.loe(now), c.issueEndAt.gt(now),
                        lectureId == null ? null
                                : c.targetType.eq(literal(CouponTargetType.LECTURE)).and(c.lectureId.eq(lectureId))
                                        .or(c.targetType.eq(literal(CouponTargetType.ANY_LECTURE))))
                .orderBy(c.createdAt.desc(), c.id.desc()), limit);
    }

    @Override
    public List<Long> findIssuableLectureIds(Instant now) {
        QCoupon c = new QCoupon("c");
        return query.select(c.lectureId).distinct().from(c)
                .where(issuable(c, now), c.targetType.eq(literal(CouponTargetType.LECTURE)))
                .orderBy(c.lectureId.asc())
                .fetch();
    }

    /**
     * 내용과 전체 수를 따로 읽는다. 전체 수는 Spring Data 페이지 조회와 같이 내용만으로 알 수 있으면(첫 페이지가 덜 찼거나 마지막 페이지)
     * 세지 않는다.
     */
    @Override
    public Page<Coupon> findIssuablePage(Collection<Long> lectureIds, Instant now, Pageable pageable) {
        QCoupon c = new QCoupon("c");
        BooleanExpression listed = c.targetType.eq(literal(CouponTargetType.ANY_LECTURE));
        if (!lectureIds.isEmpty()) {
            listed = listed.or(c.targetType.eq(literal(CouponTargetType.LECTURE)).and(c.lectureId.in(lectureIds)));
        }
        JPAQuery<Coupon> content = query.selectFrom(c)
                .where(issuable(c, now), listed)
                .orderBy(c.createdAt.desc(), c.id.desc());
        if (pageable.isPaged()) {
            // 첫 페이지는 offset을 걸지 않는다(Spring Data 페이지 조회와 같은 SQL).
            // page 상한은 두지 않는다: offset이 int 범위를 넘으면 QueryDSL이 Integer.MAX_VALUE로 줄여 넘기므로(음수로 넘치지 않는다)
            // 빈 쪽이 되고 전체 수는 그대로다. CouponRepositoryTest가 이 동작을 고정한다
            if (pageable.getOffset() > 0) {
                content.offset(pageable.getOffset());
            }
            content.limit(pageable.getPageSize());
        }
        BooleanExpression counted = listed;
        return PageableExecutionUtils.getPage(content.fetch(), pageable, () -> {
            Long total = query.select(c.count()).from(c).where(issuable(c, now), counted).fetchOne();
            return total == null ? 0 : total;
        });
    }

    /** 지금 받을 수 있는가: 발급 중(OPEN)이고 발급 기간 안(시작 ≤ now &lt; 종료). {@code CouponAvailabilityRule.isListedFor}와 같다 */
    private static BooleanExpression issuable(QCoupon c, Instant now) {
        return c.status.eq(literal(CouponStatus.OPEN)).and(c.issueStartAt.loe(now)).and(c.issueEndAt.gt(now));
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

    @Override
    public List<RepublishTargetCoupon> findRepublishTargets(Collection<CouponStatus> statuses) {
        QCoupon c = new QCoupon("c");
        return query.select(Projections.constructor(RepublishTargetCoupon.class, c.id, c.status, c.epoch))
                .from(c)
                .where(c.redisReady.eq(Expressions.TRUE), c.status.in(statuses))
                .orderBy(c.id.asc())
                .fetch();
    }

    @Override
    public List<Long> findRedisReadyIds(Collection<CouponStatus> statuses, Instant endedAfter) {
        QCoupon c = new QCoupon("c");
        return query.select(c.id).from(c)
                .where(c.redisReady.eq(Expressions.TRUE), c.status.in(statuses), c.issueEndAt.gt(endedAfter))
                .orderBy(c.id.asc())
                .fetch();
    }
}
