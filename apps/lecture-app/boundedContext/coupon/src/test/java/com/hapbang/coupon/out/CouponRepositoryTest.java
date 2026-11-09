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
import com.hapbang.coupon.domain.MemberCoupon;
import com.hapbang.coupon.domain.MemberCouponStatus;
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

    @Test
    void 가격_인상_제한은_그_강의_대상_플랫폼_쿠폰이_OPEN이거나_미사용_발급_쿠폰이_있을_때다() {
        long openLecture = 101L;
        long reservedLecture = 102L;
        long availableLecture = 103L;
        long expiredLecture = 104L;
        long sellerLecture = 105L;
        long suspendedLecture = 106L;
        save(Coupon.createPlatform(ADMIN_ID, lectureRate().lectureId(openLecture).build(), NOW), CouponStatus.OPEN);
        Long reserved = save(Coupon.createPlatform(ADMIN_ID, lectureRate().lectureId(reservedLecture).build(), NOW),
                CouponStatus.CLOSED);
        Long available = save(Coupon.createPlatform(ADMIN_ID, lectureRate().lectureId(availableLecture).build(), NOW),
                CouponStatus.CLOSED);
        Long expired = save(Coupon.createPlatform(ADMIN_ID, lectureRate().lectureId(expiredLecture).build(), NOW),
                CouponStatus.CLOSED);
        save(Coupon.createSeller(SELLER_ID, lectureRate().lectureId(sellerLecture).build(), NOW), CouponStatus.OPEN);
        save(Coupon.createPlatform(ADMIN_ID, lectureRate().lectureId(suspendedLecture).build(), NOW),
                CouponStatus.SUSPENDED);
        Instant later = NOW.plusSeconds(3_600);
        MemberCoupon reservedCoupon = MemberCoupon.issue(MEMBER_ID, reserved, "req-1", NOW, NOW, NOW);
        ReflectionTestUtils.setField(reservedCoupon, "status", MemberCouponStatus.RESERVED);
        entityManager.persist(reservedCoupon);
        entityManager.persist(MemberCoupon.issue(MEMBER_ID, available, "req-2", NOW, later, NOW));
        // 만료 시각이 지금과 같은 AVAILABLE은 미사용으로 치지 않는다
        entityManager.persist(MemberCoupon.issue(MEMBER_ID, expired, "req-3", NOW, NOW, NOW));
        entityManager.flush();

        assertThat(couponRepository.existsPriceRaiseBlocker(openLecture, NOW)).isTrue();
        // RESERVED는 만료와 관계없이 미사용이다
        assertThat(couponRepository.existsPriceRaiseBlocker(reservedLecture, NOW)).isTrue();
        assertThat(couponRepository.existsPriceRaiseBlocker(availableLecture, NOW)).isTrue();
        assertThat(couponRepository.existsPriceRaiseBlocker(expiredLecture, NOW)).isFalse();
        assertThat(couponRepository.existsPriceRaiseBlocker(sellerLecture, NOW)).isFalse();
        assertThat(couponRepository.existsPriceRaiseBlocker(suspendedLecture, NOW)).isFalse();
        assertThat(couponRepository.existsPriceRaiseBlocker(999L, NOW)).isFalse();
    }

    private Long save(Coupon coupon, CouponStatus status) {
        ReflectionTestUtils.setField(coupon, "status", status);
        return couponRepository.save(coupon).getId();
    }
}
