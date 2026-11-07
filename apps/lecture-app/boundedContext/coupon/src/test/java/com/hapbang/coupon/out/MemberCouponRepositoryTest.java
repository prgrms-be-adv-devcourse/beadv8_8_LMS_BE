package com.hapbang.coupon.out;

import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.MemberCoupon;
import com.hapbang.coupon.domain.MemberCouponStatus;
import com.hapbang.testsupport.RepositoryTest;

import jakarta.persistence.EntityManager;

@RepositoryTest
class MemberCouponRepositoryTest {

    private static final long OTHER_LECTURE_ID = 8L;
    private static final Instant EXPIRES_AT = NOW.plusSeconds(86_400);

    @Autowired
    private MemberCouponRepository memberCouponRepository;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void 같은_회원이_같은_쿠폰을_두_장_가질_수_없다() {
        Long couponId = saveCoupon(Coupon.createSeller(SELLER_ID, lectureRate().build(), NOW));
        memberCouponRepository.saveAndFlush(issue(MEMBER_ID, couponId, "req-1", NOW));

        assertThatThrownBy(() -> memberCouponRepository.saveAndFlush(issue(MEMBER_ID, couponId, "req-2", NOW)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_member_coupon_member_coupon");
    }

    @Test
    void 같은_쿠폰의_같은_발급_요청으로_두_장을_만들_수_없다() {
        Long couponId = saveCoupon(Coupon.createSeller(SELLER_ID, lectureRate().build(), NOW));
        memberCouponRepository.saveAndFlush(issue(MEMBER_ID, couponId, "req-1", NOW));

        assertThatThrownBy(() -> memberCouponRepository.saveAndFlush(issue(OTHER_MEMBER_ID, couponId, "req-1", NOW)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_member_coupon_issue_request");
    }

    @Test
    void 발급_요청_ID는_쿠폰_안에서만_유일해_다른_쿠폰은_같은_요청_ID를_쓸_수_있다() {
        Long couponA = saveCoupon(Coupon.createSeller(SELLER_ID, lectureRate().build(), NOW));
        Long couponB = saveCoupon(Coupon.createSeller(SELLER_ID, lectureRate().build(), NOW));
        memberCouponRepository.saveAndFlush(issue(MEMBER_ID, couponA, "req-1", NOW));

        memberCouponRepository.saveAndFlush(issue(MEMBER_ID, couponB, "req-1", NOW));

        assertThat(memberCouponRepository.count()).isEqualTo(2);
    }

    @Test
    void 내_쿠폰은_발급_최신순이고_상태로_거를_수_있다() {
        Long couponA = saveCoupon(Coupon.createSeller(SELLER_ID, lectureRate().build(), NOW));
        Long couponB = saveCoupon(Coupon.createSeller(SELLER_ID, lectureRate().build(), NOW));
        Long couponC = saveCoupon(Coupon.createSeller(SELLER_ID, lectureRate().build(), NOW));
        MemberCoupon older = memberCouponRepository.save(issue(MEMBER_ID, couponA, "req-a", NOW));
        MemberCoupon newer = memberCouponRepository.save(issue(MEMBER_ID, couponB, "req-b", NOW.plusSeconds(10)));
        MemberCoupon used = issue(MEMBER_ID, couponC, "req-c", NOW.plusSeconds(5));
        ReflectionTestUtils.setField(used, "status", MemberCouponStatus.USED);
        memberCouponRepository.save(used);
        memberCouponRepository.save(issue(OTHER_MEMBER_ID, couponA, "req-other", NOW));
        flushAndClear();

        assertThat(memberCouponIds(memberCouponRepository.findMyCoupons(MEMBER_ID, null)))
                .containsExactly(newer.getId(), used.getId(), older.getId());
        assertThat(memberCouponIds(memberCouponRepository.findMyCoupons(MEMBER_ID, MemberCouponStatus.AVAILABLE)))
                .containsExactly(newer.getId(), older.getId());
        assertThat(memberCouponRepository.findMyCoupons(MEMBER_ID, null).getFirst().coupon().getId())
                .isEqualTo(couponB);
    }

    @Test
    void 강의에_쓸_수_있는_쿠폰은_그_강의_대상_또는_모든_강의_대상의_만료_전_AVAILABLE이다() {
        Long thisLecture = saveCoupon(Coupon.createSeller(SELLER_ID, lectureRate().build(), NOW));
        Long anyLecture = saveCoupon(Coupon.createPlatform(ADMIN_ID, anyLectureAmount().build(), NOW));
        Long otherLecture = saveCoupon(
                Coupon.createSeller(SELLER_ID, lectureRate().lectureId(OTHER_LECTURE_ID).build(), NOW));
        Long expiredCoupon = saveCoupon(Coupon.createSeller(SELLER_ID, lectureRate().build(), NOW));
        Long usedCoupon = saveCoupon(Coupon.createSeller(SELLER_ID, lectureRate().build(), NOW));
        Long othersCoupon = saveCoupon(Coupon.createSeller(SELLER_ID, lectureRate().build(), NOW));

        MemberCoupon usable = memberCouponRepository.save(issue(MEMBER_ID, thisLecture, "req-1", NOW));
        MemberCoupon usableAny = memberCouponRepository.save(issue(MEMBER_ID, anyLecture, "req-2", NOW));
        memberCouponRepository.save(issue(MEMBER_ID, otherLecture, "req-3", NOW));
        // 만료 시각이 지금과 같으면 쓸 수 없다(expiresAt > now)
        memberCouponRepository.save(MemberCoupon.issue(MEMBER_ID, expiredCoupon, "req-4", NOW, NOW, NOW));
        MemberCoupon used = issue(MEMBER_ID, usedCoupon, "req-5", NOW);
        ReflectionTestUtils.setField(used, "status", MemberCouponStatus.USED);
        memberCouponRepository.save(used);
        memberCouponRepository.save(issue(OTHER_MEMBER_ID, othersCoupon, "req-6", NOW));
        flushAndClear();

        List<OwnedCoupon> found = memberCouponRepository.findUsableFor(MEMBER_ID, LECTURE_ID, NOW);

        assertThat(memberCouponIds(found)).containsExactlyInAnyOrder(usable.getId(), usableAny.getId());
    }

    private Long saveCoupon(Coupon coupon) {
        return couponRepository.save(coupon).getId();
    }

    private static MemberCoupon issue(long memberId, Long couponId, String requestId, Instant issuedAt) {
        return MemberCoupon.issue(memberId, couponId, requestId, issuedAt, EXPIRES_AT, issuedAt);
    }

    private static List<Long> memberCouponIds(List<OwnedCoupon> owned) {
        return owned.stream().map(o -> o.memberCoupon().getId()).toList();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
