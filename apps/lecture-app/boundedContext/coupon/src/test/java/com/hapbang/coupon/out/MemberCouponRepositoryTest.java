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

    @Test
    void 회원_중복_위반_뒤_회원과_쿠폰으로_찾은_한_장의_요청_ID로_같은_요청인지_가른다() {
        Long couponId = saveCoupon(Coupon.createSeller(SELLER_ID, lectureRate().build(), NOW));
        memberCouponRepository.saveAndFlush(issue(MEMBER_ID, couponId, "req-1", NOW));
        entityManager.clear();

        assertThat(memberCouponRepository.findByMemberIdAndCouponId(MEMBER_ID, couponId))
                .get().extracting(MemberCoupon::getIssueRequestId).isEqualTo("req-1");
        assertThat(memberCouponRepository.findByMemberIdAndCouponId(OTHER_MEMBER_ID, couponId)).isEmpty();
        // 위반 뒤에는 트랜잭션이 중단되므로 마지막에 본다
        assertThatThrownBy(() -> memberCouponRepository.saveAndFlush(issue(MEMBER_ID, couponId, "req-2", NOW)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(MemberCouponRepository.MEMBER_COUPON_UNIQUE);
    }

    @Test
    void 예약_조건부_갱신은_본인_AVAILABLE_기대_세대_만료_전일_때만_한_행을_바꾸고_세대를_올린다() {
        Long couponId = saveCoupon(Coupon.createSeller(SELLER_ID, lectureRate().build(), NOW));
        Long id = memberCouponRepository.save(issue(MEMBER_ID, couponId, "req-1", NOW)).getId();
        Long expiringId = memberCouponRepository.save(MemberCoupon.issue(OTHER_MEMBER_ID, couponId, "req-2", NOW,
                NOW, NOW)).getId();
        flushAndClear();

        // 다른 회원, 다른 기대 세대, 만료(expiresAt == now)는 0행이다
        assertThat(memberCouponRepository.reserve(id, OTHER_MEMBER_ID, 0, 500L, NOW)).isZero();
        assertThat(memberCouponRepository.reserve(id, MEMBER_ID, 1, 500L, NOW)).isZero();
        assertThat(memberCouponRepository.reserve(expiringId, OTHER_MEMBER_ID, 0, 500L, NOW)).isZero();

        assertThat(memberCouponRepository.reserve(id, MEMBER_ID, 0, 500L, NOW)).isEqualTo(1);
        assertThat(memberCouponRepository.findStateById(id)).get().satisfies(state -> {
            assertThat(state.status()).isEqualTo(MemberCouponStatus.RESERVED);
            assertThat(state.generation()).isEqualTo(1);
            assertThat(state.currentOrderItemId()).isEqualTo(500L);
        });
        // 이미 RESERVED면 같은 기대 세대로 다시 예약하지 못한다
        assertThat(memberCouponRepository.reserve(id, MEMBER_ID, 0, 501L, NOW)).isZero();
    }

    @Test
    void 해제_조건부_갱신은_RESERVED이고_세대가_같을_때만_만료_전이면_AVAILABLE_뒤면_EXPIRED로_바꾼다() {
        Long couponId = saveCoupon(Coupon.createSeller(SELLER_ID, lectureRate().build(), NOW));
        Long id = memberCouponRepository.save(issue(MEMBER_ID, couponId, "req-1", NOW)).getId();
        Long otherId = memberCouponRepository.save(issue(OTHER_MEMBER_ID, couponId, "req-2", NOW)).getId();
        memberCouponRepository.reserve(id, MEMBER_ID, 0, 500L, NOW);
        memberCouponRepository.reserve(otherId, OTHER_MEMBER_ID, 0, 501L, NOW);

        // 다른 세대는 0행이다
        assertThat(memberCouponRepository.release(id, 0, NOW)).isZero();

        assertThat(memberCouponRepository.release(id, 1, NOW)).isEqualTo(1);
        assertThat(memberCouponRepository.findStateById(id)).get().satisfies(state -> {
            assertThat(state.status()).isEqualTo(MemberCouponStatus.AVAILABLE);
            assertThat(state.generation()).isEqualTo(1);
            assertThat(state.currentOrderItemId()).isNull();
        });
        // 이미 해제했으면(RESERVED가 아니면) 0행이다
        assertThat(memberCouponRepository.release(id, 1, NOW)).isZero();

        // 만료 시각(expiresAt == now)이 지나 해제하면 EXPIRED다
        assertThat(memberCouponRepository.release(otherId, 1, EXPIRES_AT)).isEqualTo(1);
        assertThat(memberCouponRepository.findStateById(otherId)).get()
                .extracting(MemberCouponState::status).isEqualTo(MemberCouponStatus.EXPIRED);
    }

    @Test
    void 결제_완료는_RESERVED_같은_세대만_USED로_바꾸고_취소_완료는_RESERVED_USED_같은_세대만_돌린다() {
        Long couponId = saveCoupon(Coupon.createSeller(SELLER_ID, lectureRate().build(), NOW));
        Long id = memberCouponRepository.save(issue(MEMBER_ID, couponId, "req-1", NOW)).getId();
        Long reservedId = memberCouponRepository.save(issue(OTHER_MEMBER_ID, couponId, "req-2", NOW)).getId();
        memberCouponRepository.reserve(id, MEMBER_ID, 0, 500L, NOW);
        memberCouponRepository.reserve(reservedId, OTHER_MEMBER_ID, 0, 501L, NOW);

        // 결제 완료: 다른 세대는 0행, 기한이 지나도 바꾼다
        assertThat(memberCouponRepository.markUsed(id, 0, NOW)).isZero();
        assertThat(memberCouponRepository.markUsed(id, 1, EXPIRES_AT)).isEqualTo(1);
        assertThat(memberCouponRepository.markUsed(id, 1, EXPIRES_AT)).isZero();
        assertThat(memberCouponRepository.findStateById(id)).get()
                .extracting(MemberCouponState::status).isEqualTo(MemberCouponStatus.USED);

        // 취소 완료: USED는 만료 전이면 AVAILABLE, 다른 세대는 0행, 이미 돌렸으면 0행
        assertThat(memberCouponRepository.restore(id, 0, NOW)).isZero();
        assertThat(memberCouponRepository.restore(id, 1, NOW)).isEqualTo(1);
        assertThat(memberCouponRepository.findStateById(id)).get().satisfies(state -> {
            assertThat(state.status()).isEqualTo(MemberCouponStatus.AVAILABLE);
            assertThat(state.currentOrderItemId()).isNull();
        });
        assertThat(memberCouponRepository.restore(id, 1, NOW)).isZero();

        // 결제 완료 전 취소 완료: RESERVED도 돌리고, 만료 시각(expiresAt == now)이면 EXPIRED다
        assertThat(memberCouponRepository.restore(reservedId, 1, EXPIRES_AT)).isEqualTo(1);
        assertThat(memberCouponRepository.findStateById(reservedId)).get()
                .extracting(MemberCouponState::status).isEqualTo(MemberCouponStatus.EXPIRED);
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
