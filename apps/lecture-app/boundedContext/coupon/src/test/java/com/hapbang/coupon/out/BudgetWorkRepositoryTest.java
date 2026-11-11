package com.hapbang.coupon.out;

import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import com.hapbang.coupon.domain.BudgetWork;
import com.hapbang.coupon.domain.BudgetWorkKind;
import com.hapbang.coupon.domain.BudgetWorkStatus;
import com.hapbang.coupon.domain.Coupon;
import com.hapbang.testsupport.RepositoryTest;

import jakarta.persistence.EntityManager;

@RepositoryTest
class BudgetWorkRepositoryTest {

    @Autowired
    private BudgetWorkRepository budgetWorkRepository;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void 작업_lease는_한번만_선점되고_만료_뒤에는_새_attempt로_재선점한다() {
        Coupon coupon = couponRepository.save(Coupon.createPlatform(ADMIN_ID, lectureRate().build(), NOW));
        String id = BudgetWork.reserve(coupon, BudgetWork.FIRST_ATTEMPT, NOW).getWorkId();
        budgetWorkRepository.saveAndFlush(BudgetWork.reserve(coupon, BudgetWork.FIRST_ATTEMPT, NOW));
        entityManager.clear();
        assertThat(budgetWorkRepository.claim(id, NOW, NOW.plusSeconds(60))).isEqualTo(1);
        assertThat(budgetWorkRepository.claim(id, NOW.plusSeconds(59), NOW.plusSeconds(120))).isZero();
        entityManager.clear();
        assertThat(budgetWorkRepository.findById(id).orElseThrow().getAttempt()).isEqualTo(1);
        assertThat(budgetWorkRepository.claim(id, NOW.plusSeconds(60), NOW.plusSeconds(120))).isEqualTo(1);
        entityManager.clear();
        BudgetWork work = budgetWorkRepository.findById(id).orElseThrow();
        assertThat(work.getAttempt()).isEqualTo(2);
        work.markDone("PB-1", NOW.plusSeconds(61));
        entityManager.flush();
        assertThat(budgetWorkRepository.claim(id, NOW.plusSeconds(130), NOW.plusSeconds(190))).isZero();
    }

    @Test
    void 재시도는_결정적_작업_ID로_기존_작업을_찾고_같은_ID를_새로_저장하지_않는다() {
        Coupon coupon = couponRepository.save(Coupon.createPlatform(ADMIN_ID, lectureRate().build(), NOW));
        budgetWorkRepository.save(BudgetWork.reserve(coupon, BudgetWork.FIRST_ATTEMPT, NOW));
        entityManager.flush();
        entityManager.clear();

        BudgetWork found = budgetWorkRepository.findById(coupon.budgetReserveWorkId()).orElseThrow();
        found.markDone("reservation-1", NOW.plusSeconds(1));
        entityManager.flush();
        entityManager.clear();

        assertThat(budgetWorkRepository.findByCouponIdAndKindOrderByCreatedAtAsc(coupon.getId(), BudgetWorkKind.RESERVE))
                .singleElement()
                .satisfies(work -> {
                    assertThat(work.getStatus()).isEqualTo(BudgetWorkStatus.DONE);
                    assertThat(work.getResult()).isEqualTo("reservation-1");
                    assertThat(work.getAmount()).isEqualTo(100_000L);
                });
        assertThat(budgetWorkRepository.findByCouponIdAndKindOrderByCreatedAtAsc(coupon.getId(), BudgetWorkKind.RELEASE))
                .isEmpty();
        // 같은 작업 ID의 새 작업은 완료된 작업을 덮지 않고 PK 위반이다
        assertThatThrownBy(() -> budgetWorkRepository.saveAndFlush(BudgetWork.reserve(coupon, BudgetWork.FIRST_ATTEMPT, NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
