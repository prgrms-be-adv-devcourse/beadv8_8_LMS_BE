package com.hapbang.coupon.out;

import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.*;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponStatus;
import com.hapbang.coupon.domain.CouponType;
import com.hapbang.testsupport.RepositoryTest;

import jakarta.persistence.EntityManager;

@RepositoryTest
class CouponRepositoryTest {

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void 쿠폰_스키마에_저장한_쿠폰을_발행_조건과_상태_그대로_읽는다() {
        Instant fixed = Instant.parse("2026-12-31T14:59:59.123456Z");
        Coupon coupon = Coupon.createPlatform(ADMIN_ID, anyLectureRate().fixed(fixed).stackable(true).build(), NOW);
        coupon.recordBudgetReserved("reservation-1", NOW);
        coupon.markRedisReady(NOW);
        coupon.open(NOW.plusSeconds(1));
        Long id = couponRepository.saveAndFlush(coupon).getId();
        entityManager.clear();

        Coupon found = couponRepository.findById(id).orElseThrow();

        assertThat(found.getType()).isEqualTo(CouponType.PLATFORM);
        assertThat(found.getStatus()).isEqualTo(CouponStatus.OPEN);
        assertThat(found.terms()).isEqualTo(anyLectureRate().fixed(fixed).stackable(true).build());
        assertThat(found.getBudgetReservationId()).isEqualTo("reservation-1");
        assertThat(found.isRedisReady()).isTrue();
        assertThat(found.getEpoch()).isEqualTo(Coupon.INITIAL_EPOCH);
        assertThat(found.getPolicyVersion()).isEqualTo(Coupon.INITIAL_POLICY_VERSION);
        assertThat(found.getUpdatedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(found.getVersion()).isZero();
        assertThat(found.budgetReserveWorkId()).isEqualTo("coupon-" + id + "-reserve-v1");
    }

    @Test
    void 도메인이_받은_경계_시각은_DB_CHECK도_통과한다() {
        // 발급 기간 1마이크로초, 고정 만료는 종료 1마이크로초 뒤(나노초는 도메인이 마이크로초로 잘라 DB와 같게 비교한다)
        Instant end = ISSUE_END.plusNanos(1_500);
        Coupon coupon = Coupon.createSeller(SELLER_ID,
                lectureRate().issuePeriod(end.minusNanos(1_000), end).fixed(end.plusNanos(1_000)).build(), NOW);

        assertThatCode(() -> couponRepository.saveAndFlush(coupon)).doesNotThrowAnyException();
    }

    @Test
    void 확정_발급_수는_수량을_넘을_수_없다() {
        Coupon coupon = Coupon.createSeller(SELLER_ID, lectureRate().quantity(1).build(), NOW);
        ReflectionTestUtils.setField(coupon, "issuedCount", 2);

        assertThatThrownBy(() -> couponRepository.saveAndFlush(coupon))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
