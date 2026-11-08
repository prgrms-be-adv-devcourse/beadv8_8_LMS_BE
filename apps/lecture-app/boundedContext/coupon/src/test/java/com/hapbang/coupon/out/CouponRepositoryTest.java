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
        // 발급 기간 1밀리초, 고정 만료는 종료 1밀리초 뒤(밀리초 아래는 도메인이 Redis와 같게 잘라 비교한다)
        Instant end = ISSUE_END.plusNanos(1_500_000);
        Coupon coupon = Coupon.createSeller(SELLER_ID,
                lectureRate().issuePeriod(end.minusMillis(1), end).fixed(end.plusMillis(1)).build(), NOW);

        assertThatCode(() -> couponRepository.saveAndFlush(coupon)).doesNotThrowAnyException();
    }

    @Test
    void 확정_발급_수는_수량을_넘을_수_없다() {
        Coupon coupon = Coupon.createSeller(SELLER_ID, lectureRate().quantity(1).build(), NOW);
        ReflectionTestUtils.setField(coupon, "issuedCount", 2);

        assertThatThrownBy(() -> couponRepository.saveAndFlush(coupon))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 확정_발급_수는_총수량까지만_조건부로_오르고_그다음은_0행이다() {
        Coupon coupon = Coupon.createSeller(SELLER_ID, lectureRate().quantity(2).build(), NOW);
        Long id = couponRepository.saveAndFlush(coupon).getId();
        long versionBefore = coupon.getVersion();

        assertThat(couponRepository.incrementIssuedCount(id, Coupon.INITIAL_EPOCH)).isEqualTo(1);
        assertThat(couponRepository.incrementIssuedCount(id, Coupon.INITIAL_EPOCH)).isEqualTo(1);
        assertThat(couponRepository.incrementIssuedCount(id, Coupon.INITIAL_EPOCH)).isZero();
        entityManager.clear();

        Coupon found = couponRepository.findById(id).orElseThrow();
        assertThat(found.getIssuedCount()).isEqualTo(2);
        // 발급 확정은 관리자 상태 변경의 낙관적 락·Redis fence(version)를 흔들지 않는다
        assertThat(found.getVersion()).isEqualTo(versionBefore);
    }

    @Test
    void 쿠폰의_epoch가_요청의_epoch와_다르면_확정_발급_수를_올리지_않는다() {
        Coupon coupon = Coupon.createSeller(SELLER_ID, lectureRate().quantity(5).build(), NOW);
        Long id = couponRepository.saveAndFlush(coupon).getId();

        // 복구 재개가 새 epoch를 활성화한 뒤 이전 epoch의 확정이 온다
        assertThat(couponRepository.incrementIssuedCount(id, Coupon.INITIAL_EPOCH + 1)).isZero();
        entityManager.clear();

        assertThat(couponRepository.findById(id).orElseThrow().getIssuedCount()).isZero();
    }

    @Test
    void 쿠폰_상태를_바꿔_저장해도_읽어_둔_옛_확정_발급_수로_덮지_않는다() {
        Coupon coupon = Coupon.createSeller(SELLER_ID, lectureRate().quantity(5).build(), NOW);
        Long id = couponRepository.saveAndFlush(coupon).getId();
        entityManager.clear();
        Coupon loaded = couponRepository.findById(id).orElseThrow();

        // 관리자가 읽어 둔 뒤(확정 발급 수 0) 소비자가 한 장을 확정하고, 관리자가 상태를 바꿔 저장한다
        couponRepository.incrementIssuedCount(id, Coupon.INITIAL_EPOCH);
        loaded.submitReview(NOW.plusSeconds(1));
        couponRepository.saveAndFlush(loaded);
        entityManager.clear();

        assertThat(couponRepository.findById(id).orElseThrow().getIssuedCount()).isEqualTo(1);
    }
}
