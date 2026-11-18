package com.hapbang.coupon.out;

import static com.hapbang.coupon.out.CouponQuerydslSupport.literal;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Limit;

import com.hapbang.coupon.app.port.BudgetWorkRepositoryCustom;
import com.hapbang.coupon.domain.BudgetWorkStatus;
import com.hapbang.coupon.domain.QBudgetWork;
import com.querydsl.jpa.impl.JPAQueryFactory;

import jakarta.persistence.EntityManager;

class BudgetWorkRepositoryCustomImpl implements BudgetWorkRepositoryCustom {

    private final JPAQueryFactory query;

    BudgetWorkRepositoryCustomImpl(EntityManager entityManager) {
        this.query = CouponQuerydslSupport.queryFactory(entityManager);
    }

    @Override
    public List<String> findDueIds(Instant now, Limit limit) {
        QBudgetWork w = new QBudgetWork("w");
        return CouponQuerydslSupport.fetch(query.select(w.workId).from(w)
                .where(w.status.eq(literal(BudgetWorkStatus.PENDING)),
                        w.leaseUntil.isNull().or(w.leaseUntil.loe(now)))
                .orderBy(w.updatedAt.asc(), w.workId.asc()), limit);
    }
}
