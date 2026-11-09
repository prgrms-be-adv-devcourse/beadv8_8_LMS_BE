package com.hapbang.coupon.out;

import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
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
    void 강의에_받을_수_있는_쿠폰은_발급_중이고_기간_안인_그_강의_또는_모든_강의_대상이며_최신순이다() {
        Instant now = ISSUE_START.plusSeconds(1);
        Long older = save(Coupon.createSeller(SELLER_ID, lectureRate().build(), NOW), CouponStatus.OPEN);
        Long anyLecture = save(Coupon.createPlatform(ADMIN_ID, anyLectureAmount().build(), NOW.plusSeconds(1)),
                CouponStatus.OPEN);
        Long newer = save(Coupon.createSeller(SELLER_ID, lectureRate().build(), NOW.plusSeconds(2)), CouponStatus.OPEN);
        // 넣지 않는 쿠폰: 다른 강의, 발급 중이 아님, 시작 전, 끝남(종료 시각 = now)
        save(Coupon.createSeller(SELLER_ID, lectureRate().lectureId(8L).build(), NOW), CouponStatus.OPEN);
        save(Coupon.createSeller(SELLER_ID, lectureRate().build(), NOW), CouponStatus.SUSPENDED);
        save(Coupon.createSeller(SELLER_ID, lectureRate().issuePeriod(now.plusMillis(1), ISSUE_END).build(), NOW),
                CouponStatus.OPEN);
        save(Coupon.createSeller(SELLER_ID, lectureRate().issuePeriod(NOW.plusSeconds(1), now).build(), NOW),
                CouponStatus.OPEN);
        entityManager.flush();
        entityManager.clear();

        List<Coupon> found = couponRepository.findIssuableFor(LECTURE_ID, now, Limit.unlimited());

        assertThat(found).extracting(Coupon::getId).containsExactly(newer, anyLecture, older);
        assertThat(couponRepository.findIssuableFor(LECTURE_ID, now, Limit.of(2))).extracting(Coupon::getId)
                .containsExactly(newer, anyLecture);
    }

    @Test
    void 판매자_목록은_본인의_판매자_쿠폰만_상태별로_최신순_Limit만큼_읽고_관리자_목록은_모든_쿠폰을_읽는다() {
        Long old = save(Coupon.createSeller(SELLER_ID, lectureRate().build(), NOW), CouponStatus.DRAFT);
        // 같은 시각에 만든 두 쿠폰은 ID 역순이다
        Long tieFirst = save(Coupon.createSeller(SELLER_ID, lectureRate().build(), NOW.plusSeconds(1)),
                CouponStatus.REVIEW);
        Long tieSecond = save(Coupon.createSeller(SELLER_ID, lectureRate().build(), NOW.plusSeconds(1)),
                CouponStatus.DRAFT);
        Long newest = save(Coupon.createSeller(SELLER_ID, lectureRate().build(), NOW.plusSeconds(2)),
                CouponStatus.DRAFT);
        // 판매자 목록에 넣지 않는 쿠폰: 다른 판매자의 쿠폰, 같은 회원 ID가 신청자인 플랫폼 쿠폰
        Long otherSeller = save(Coupon.createSeller(SELLER_ID + 1, lectureRate().build(), NOW.plusSeconds(3)),
                CouponStatus.DRAFT);
        Long platform = save(Coupon.createPlatform(SELLER_ID, anyLectureAmount().build(), NOW.plusSeconds(4)),
                CouponStatus.DRAFT);
        entityManager.flush();
        entityManager.clear();

        assertThat(couponRepository.findSellerCoupons(SELLER_ID, null, Limit.unlimited()))
                .extracting(Coupon::getId).containsExactly(newest, tieSecond, tieFirst, old);
        assertThat(couponRepository.findSellerCoupons(SELLER_ID, CouponStatus.DRAFT, Limit.unlimited()))
                .extracting(Coupon::getId).containsExactly(newest, tieSecond, old);
        assertThat(couponRepository.findSellerCoupons(SELLER_ID, null, Limit.of(2)))
                .extracting(Coupon::getId).containsExactly(newest, tieSecond);

        assertThat(couponRepository.findAllCoupons(null, Limit.unlimited()))
                .extracting(Coupon::getId).containsExactly(platform, otherSeller, newest, tieSecond, tieFirst, old);
        assertThat(couponRepository.findAllCoupons(CouponStatus.REVIEW, Limit.unlimited()))
                .extracting(Coupon::getId).containsExactly(tieFirst);
        assertThat(couponRepository.findAllCoupons(CouponStatus.DRAFT, Limit.of(3)))
                .extracting(Coupon::getId).containsExactly(platform, otherSeller, newest);
    }

    private Long save(Coupon coupon, CouponStatus status) {
        ReflectionTestUtils.setField(coupon, "status", status);
        return couponRepository.save(coupon).getId();
    }
}
