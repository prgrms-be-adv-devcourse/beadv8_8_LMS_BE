package com.hapbang.coupon.out;

import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
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

    @Autowired
    private JdbcTemplate jdbc;

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

    @Test
    void 만료_대상은_기한이_지난_AVAILABLE_쿠폰을_유형과_관계없이_고른다() {
        Long seller = saveCoupon(Coupon.createSeller(SELLER_ID, lectureRate().build(), NOW));
        Long platform = saveCoupon(Coupon.createPlatform(ADMIN_ID, lectureRate().build(), NOW));
        Long atNow = memberCouponRepository.save(MemberCoupon.issue(1L, seller, "req-1", NOW, NOW, NOW)).getId();
        Long earlier = memberCouponRepository.save(MemberCoupon.issue(2L, seller, "req-2", NOW,
                NOW.minusSeconds(10), NOW)).getId();
        Long future = memberCouponRepository.save(MemberCoupon.issue(3L, seller, "req-3", NOW,
                NOW.plusSeconds(1), NOW)).getId();
        Long reserved = memberCouponRepository.save(MemberCoupon.issue(4L, seller, "req-4", NOW,
                NOW.minusSeconds(10), NOW)).getId();
        Long platformExpired = memberCouponRepository.save(MemberCoupon.issue(5L, platform, "req-5", NOW,
                NOW.minusSeconds(20), NOW)).getId();
        memberCouponRepository.reserve(reserved, 4L, 0, 500L, NOW.minusSeconds(100));
        flushAndClear();

        // 만료 시각(expiresAt == now) 포함, 플랫폼도 함께 고르고 미래 만료·RESERVED는 고르지 않는다
        assertThat(memberCouponRepository.findExpiredCouponIds(NOW, Limit.of(10)))
                .containsExactly(platformExpired, earlier, atNow);
        assertThat(memberCouponRepository.findExpiredCouponIds(NOW, Limit.of(1))).containsExactly(platformExpired);

        // 조건부 갱신은 넘긴 ID 중 AVAILABLE이고 기한이 지난 것만 바꾼다
        assertThat(memberCouponRepository.expire(List.of(earlier, atNow, future, reserved, platformExpired), NOW)).isEqualTo(3);
        assertThat(memberCouponRepository.findStateById(atNow)).get().satisfies(state -> {
            assertThat(state.status()).isEqualTo(MemberCouponStatus.EXPIRED);
            assertThat(state.generation()).isZero();
        });
        assertThat(memberCouponRepository.findStateById(earlier)).get()
                .extracting(MemberCouponState::status).isEqualTo(MemberCouponStatus.EXPIRED);
        assertThat(memberCouponRepository.findStateById(future)).get()
                .extracting(MemberCouponState::status).isEqualTo(MemberCouponStatus.AVAILABLE);
        assertThat(memberCouponRepository.findStateById(reserved)).get().satisfies(state -> {
            assertThat(state.status()).isEqualTo(MemberCouponStatus.RESERVED);
            assertThat(state.generation()).isEqualTo(1);
        });
        assertThat(memberCouponRepository.findStateById(platformExpired)).get()
                .extracting(MemberCouponState::status).isEqualTo(MemberCouponStatus.EXPIRED);

        // 두 번 돌아도 결과가 같다
        assertThat(memberCouponRepository.expire(List.of(earlier, atNow), NOW)).isZero();
        assertThat(memberCouponRepository.findExpiredCouponIds(NOW, Limit.of(10))).isEmpty();
    }

    @Test
    void 만료_cursor는_같은_기한의_실패_AVAILABLE이_남아도_마지막_ID_다음부터_고른다() {
        long couponId = saveCoupon(Coupon.createSeller(SELLER_ID, lectureRate().build(), NOW));
        long first = memberCouponRepository.save(MemberCoupon.issue(1L, couponId, "cursor-1", NOW, NOW, NOW)).getId();
        long second = memberCouponRepository.save(MemberCoupon.issue(2L, couponId, "cursor-2", NOW, NOW, NOW)).getId();
        long third = memberCouponRepository.save(MemberCoupon.issue(3L, couponId, "cursor-3", NOW, NOW, NOW)).getId();
        flushAndClear();

        assertThat(memberCouponRepository.findExpiredCouponTargets(NOW, null, 0, Limit.of(2)))
                .extracting(MemberCouponExpiryTarget::id).containsExactly(first, second);
        memberCouponRepository.expire(List.of(second), NOW);
        assertThat(memberCouponRepository.findExpiredCouponTargets(NOW, NOW, second, Limit.of(2)))
                .extracting(MemberCouponExpiryTarget::id).containsExactly(third);
        assertThat(memberCouponRepository.findStateById(first).orElseThrow().status()).isEqualTo(MemberCouponStatus.AVAILABLE);
    }

    @Test
    void 예산_등록_완료는_0원도_현재상태로_표시하고_상태가_바뀌면_재선택한다() {
        long couponId = saveCoupon(Coupon.createPlatform(ADMIN_ID, lectureRate().build(), NOW));
        long used = memberCouponRepository.save(MemberCoupon.issue(1L, couponId, "checked-used", NOW, NOW, NOW)).getId();
        long expired = memberCouponRepository.save(MemberCoupon.issue(2L, couponId, "checked-expired", NOW, NOW, NOW)).getId();
        memberCouponRepository.reserve(used, 1L, 0, 500L, NOW.minusSeconds(1));
        memberCouponRepository.markUsed(used, 1, NOW);
        memberCouponRepository.expire(List.of(expired), NOW);
        flushAndClear();

        assertThat(memberCouponRepository.findExpiredPlatformBudgetIds(couponId, NOW, 0, Limit.of(10))).containsExactly(used, expired);
        assertThat(memberCouponRepository.planBudgetRelease(used, 0, MemberCouponStatus.USED, 0, 0, NOW)).isZero();
        assertThat(memberCouponRepository.planBudgetRelease(used, 1, MemberCouponStatus.EXPIRED, 0, 0, NOW)).isZero();
        assertThat(memberCouponRepository.planBudgetRelease(used, 1, MemberCouponStatus.USED, 1, 1, NOW)).isZero();
        assertThat(memberCouponRepository.planBudgetRelease(used, 1, MemberCouponStatus.USED, 0, 0, NOW)).isEqualTo(1);
        assertThat(memberCouponRepository.planBudgetRelease(used, 1, MemberCouponStatus.USED, 0, 0, NOW)).isZero();
        assertThat(memberCouponRepository.planBudgetRelease(expired, 0, MemberCouponStatus.EXPIRED, 0, 1_000, NOW)).isEqualTo(1);
        assertThat(memberCouponRepository.findBudgetState(used).orElseThrow().checkedStatus()).isEqualTo(MemberCouponStatus.USED);
        assertThat(memberCouponRepository.findExpiredPlatformBudgetIds(couponId, NOW, 0, Limit.of(10))).isEmpty();

        // 완료 표식을 모르는 이전 writer의 복원 UPDATE도 status 변경 자체로 재선택된다.
        memberCouponRepository.restore(used, 1, NOW);
        assertThat(memberCouponRepository.findExpiredPlatformBudgetIds(couponId, NOW, 0, Limit.of(10))).containsExactly(used);
        assertThat(memberCouponRepository.planBudgetRelease(used, 1, MemberCouponStatus.EXPIRED, 0, 1_000, NOW)).isEqualTo(1);
        assertThat(memberCouponRepository.findBudgetState(used).orElseThrow().checkedStatus()).isEqualTo(MemberCouponStatus.EXPIRED);
        assertThat(memberCouponRepository.findExpiredPlatformBudgetIds(couponId, NOW, 0, Limit.of(10))).isEmpty();
    }

    @Test
    void 판매자_만료가_많아도_플랫폼_미확인_건만_선택하며_로컬_조회_계획을_남긴다() throws Exception {
        final int sellerCount = 20_000;
        final int completedPlatformCount = 20_000;
        long seller = saveCoupon(Coupon.createSeller(SELLER_ID, lectureRate().quantity(sellerCount).build(), NOW));
        long platform = saveCoupon(Coupon.createPlatform(ADMIN_ID, lectureRate().build(), NOW));
        long completedPlatform = saveCoupon(Coupon.createPlatform(ADMIN_ID,
                lectureRate().quantity(completedPlatformCount).build(), NOW));
        entityManager.flush();
        jdbc.update("""
                insert into coupon.member_coupon (member_id, coupon_id, issue_request_id, status, generation,
                    issued_at, expires_at, created_at, updated_at)
                select n, ?, 'plan-seller-' || n, 'EXPIRED', 0, ?, ?, ?, ?
                from generate_series(1, ?) n
                """, seller, java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW), sellerCount);
        jdbc.update("""
                insert into coupon.member_coupon (member_id, coupon_id, issue_request_id, status, generation,
                    issued_at, expires_at, created_at, updated_at, budget_release_planned_hp, budget_release_checked_status)
                select n, ?, 'plan-complete-' || n, 'EXPIRED', 0, ?, ?, ?, ?, 1000, 'EXPIRED'
                from generate_series(1, ?) n
                """, completedPlatform, java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW), completedPlatformCount);
        MemberCoupon pending = MemberCoupon.issue(MEMBER_ID, platform, "plan-platform", NOW, NOW, NOW);
        ReflectionTestUtils.setField(pending, "status", MemberCouponStatus.EXPIRED);
        long platformMember = memberCouponRepository.saveAndFlush(pending).getId();
        jdbc.execute("analyze coupon.coupon");
        jdbc.execute("analyze coupon.member_coupon");

        assertThat(couponRepository.findPlatformMemberBudgetCouponIds(NOW, 0, Limit.of(100))).containsExactly(platform);
        assertThat(memberCouponRepository.findExpiredPlatformBudgetIds(platform, NOW, 0, Limit.of(100)))
                .containsExactly(platformMember);
        String policiesPlan = jdbc.queryForObject("""
                explain (analyze, buffers, format json)
                select c.id from coupon.coupon c
                where c.type = 'PLATFORM' and c.id > 0
                  and exists (select 1 from coupon.member_coupon mc where mc.coupon_id = c.id and mc.expires_at <= ?
                    and mc.status in ('USED', 'EXPIRED')
                    and (mc.budget_release_checked_status is null or mc.budget_release_checked_status <> mc.status))
                order by c.id limit 100
                """, String.class, java.sql.Timestamp.from(NOW));
        String membersPlan = jdbc.queryForObject("""
                explain (analyze, buffers, format json)
                select mc.id from coupon.member_coupon mc
                where mc.coupon_id = ? and mc.id > 0 and mc.expires_at <= ?
                  and mc.status in ('USED', 'EXPIRED')
                  and (mc.budget_release_checked_status is null or mc.budget_release_checked_status <> mc.status)
                order by mc.id limit 100
                """, String.class, platform, java.sql.Timestamp.from(NOW));
        Path artifact = Path.of("build", "reports", "budget-release-query-plan.json");
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, "{\"sellerExpiredRows\":" + sellerCount
                + ",\"platformCompletedRows\":" + completedPlatformCount
                + ",\"platformPendingRows\":1,\"policies\":" + policiesPlan + ",\"members\":" + membersPlan + "}");
    }

    @Test
    void 예산_후보_정책은_미확인_만료_플랫폼만_정책_ID_cursor로_고른다() {
        long seller = saveCoupon(Coupon.createSeller(SELLER_ID, lectureRate().build(), NOW));
        long pendingA = saveCoupon(Coupon.createPlatform(ADMIN_ID, lectureRate().build(), NOW));
        long complete = saveCoupon(Coupon.createPlatform(ADMIN_ID, lectureRate().build(), NOW));
        long future = saveCoupon(Coupon.createPlatform(ADMIN_ID, lectureRate().build(), NOW));
        long pendingB = saveCoupon(Coupon.createPlatform(ADMIN_ID, lectureRate().build(), NOW));
        budgetMember(seller, MemberCouponStatus.EXPIRED, null, NOW);
        long first = budgetMember(pendingA, MemberCouponStatus.EXPIRED, null, NOW);
        budgetMember(complete, MemberCouponStatus.EXPIRED, MemberCouponStatus.EXPIRED, NOW);
        budgetMember(future, MemberCouponStatus.EXPIRED, null, NOW.plusSeconds(1));
        long second = budgetMember(pendingB, MemberCouponStatus.USED, null, NOW);
        flushAndClear();

        assertThat(couponRepository.findPlatformMemberBudgetCouponIds(NOW, 0, Limit.of(1))).containsExactly(pendingA);
        assertThat(couponRepository.findPlatformMemberBudgetCouponIds(NOW, pendingA, Limit.of(10))).containsExactly(pendingB);
        assertThat(memberCouponRepository.findExpiredPlatformBudgetIds(pendingA, NOW, 0, Limit.of(10)))
                .containsExactly(first);
        assertThat(memberCouponRepository.findExpiredPlatformBudgetIds(pendingB, NOW, 0, Limit.of(10)))
                .containsExactly(second);
        assertThat(memberCouponRepository.findExpiredPlatformBudgetIds(pendingA, NOW, first, Limit.of(10))).isEmpty();
        memberCouponRepository.planBudgetRelease(first, 0, MemberCouponStatus.EXPIRED, 0, 1_000, NOW);
        memberCouponRepository.planBudgetRelease(second, 0, MemberCouponStatus.USED, 0, 0, NOW);
        assertThat(couponRepository.findPlatformMemberBudgetCouponIds(NOW, 0, Limit.of(10))).isEmpty();
    }

    private long budgetMember(long couponId, MemberCouponStatus status, MemberCouponStatus checked, Instant expiresAt) {
        MemberCoupon member = MemberCoupon.issue(MEMBER_ID, couponId, "budget-member", NOW, expiresAt, NOW);
        ReflectionTestUtils.setField(member, "status", status);
        ReflectionTestUtils.setField(member, "budgetReleaseCheckedStatus", checked);
        return memberCouponRepository.saveAndFlush(member).getId();
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
