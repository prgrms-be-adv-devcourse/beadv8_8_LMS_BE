package com.hapbang.coupon.out;

import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.function.Supplier;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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

        assertThat(memberCouponRepository.findExpiredPlatformBudgetTargets(couponId, NOW, null, 0, Limit.of(10))).extracting(MemberCouponExpiryTarget::id).containsExactly(used, expired);
        assertThat(memberCouponRepository.planBudgetRelease(used, 0, MemberCouponStatus.USED, 0, 0, NOW)).isZero();
        assertThat(memberCouponRepository.planBudgetRelease(used, 1, MemberCouponStatus.EXPIRED, 0, 0, NOW)).isZero();
        assertThat(memberCouponRepository.planBudgetRelease(used, 1, MemberCouponStatus.USED, 1, 1, NOW)).isZero();
        assertThat(memberCouponRepository.planBudgetRelease(used, 1, MemberCouponStatus.USED, 0, 0, NOW)).isEqualTo(1);
        assertThat(memberCouponRepository.planBudgetRelease(used, 1, MemberCouponStatus.USED, 0, 0, NOW)).isZero();
        assertThat(memberCouponRepository.planBudgetRelease(expired, 0, MemberCouponStatus.EXPIRED, 0, 1_000, NOW)).isEqualTo(1);
        assertThat(memberCouponRepository.findBudgetState(used).orElseThrow().checkedStatus()).isEqualTo(MemberCouponStatus.USED);
        assertThat(memberCouponRepository.findExpiredPlatformBudgetTargets(couponId, NOW, null, 0, Limit.of(10))).isEmpty();

        // 완료 표식을 모르는 이전 writer의 복원 UPDATE도 status 변경 자체로 재선택된다.
        memberCouponRepository.restore(used, 1, NOW);
        assertThat(memberCouponRepository.findExpiredPlatformBudgetTargets(couponId, NOW, null, 0, Limit.of(10))).extracting(MemberCouponExpiryTarget::id).containsExactly(used);
        assertThat(memberCouponRepository.planBudgetRelease(used, 1, MemberCouponStatus.EXPIRED, 0, 1_000, NOW)).isEqualTo(1);
        assertThat(memberCouponRepository.findBudgetState(used).orElseThrow().checkedStatus()).isEqualTo(MemberCouponStatus.EXPIRED);
        assertThat(memberCouponRepository.findExpiredPlatformBudgetTargets(couponId, NOW, null, 0, Limit.of(10))).isEmpty();
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

        var policies = captureRepositorySql(() -> couponRepository.findPlatformMemberBudgetCouponIds(NOW, 0, Limit.of(100)));
        var members = captureRepositorySql(() -> memberCouponRepository.findExpiredPlatformBudgetTargets(platform, NOW, null, 0, Limit.of(100)));
        assertThat(policies.result()).containsExactly(platform);
        assertThat(members.result()).extracting(MemberCouponExpiryTarget::id).containsExactly(platformMember);
        var policyPlan = explain("policies", policies, 0L, java.sql.Timestamp.from(NOW), 100);
        var memberPlan = explain("members", members, platform, java.sql.Timestamp.from(NOW), 100);
        writePlans("budget-release-query-plan.json", Map.of("sellerExpiredRows", sellerCount,
                "platformCompletedRows", completedPlatformCount, "platformPendingRows", 1), policyPlan, memberPlan);
        assertBoundedPlan(policyPlan, 1);
        assertBoundedPlan(memberPlan, 1);
    }

    @Test
    void 미래_USED가_많아도_만료_소수와_0건_조회는_기한_범위만_읽는다() throws Exception {
        final int futureUsedCount = 20_000;
        final int sellerExpiredCount = 20_000;
        long platform = saveCoupon(Coupon.createPlatform(ADMIN_ID, lectureRate().quantity(futureUsedCount + 2).build(), NOW));
        long seller = saveCoupon(Coupon.createSeller(SELLER_ID, lectureRate().quantity(sellerExpiredCount).build(), NOW));
        entityManager.flush();
        jdbc.update("""
                insert into coupon.member_coupon (member_id, coupon_id, issue_request_id, status, generation,
                    issued_at, expires_at, created_at, updated_at)
                select n, ?, 'plan-seller-' || n, 'EXPIRED', 0, ?, ?, ?, ?
                from generate_series(1, ?) n
                """, seller, java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW), sellerExpiredCount);
        jdbc.update("""
                insert into coupon.member_coupon (member_id, coupon_id, issue_request_id, status, generation,
                    issued_at, expires_at, created_at, updated_at)
                select n, ?, 'plan-future-' || n, 'USED', 1, ?, ?, ?, ?
                from generate_series(1, ?) n
                """, platform, java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW.plusSeconds(86_400)),
                java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW), futureUsedCount);
        MemberCoupon newer = MemberCoupon.issue(futureUsedCount + 1L, platform, "plan-due-newer", NOW, NOW, NOW);
        ReflectionTestUtils.setField(newer, "status", MemberCouponStatus.EXPIRED);
        long newerId = memberCouponRepository.saveAndFlush(newer).getId();
        Instant earlierExpiry = NOW.minusSeconds(60);
        MemberCoupon older = MemberCoupon.issue(futureUsedCount + 2L, platform, "plan-due-older", NOW, earlierExpiry, NOW);
        ReflectionTestUtils.setField(older, "status", MemberCouponStatus.EXPIRED);
        long olderId = memberCouponRepository.saveAndFlush(older).getId();
        jdbc.execute("analyze coupon.coupon");
        jdbc.execute("analyze coupon.member_coupon");

        var policies = captureRepositorySql(() -> couponRepository.findPlatformMemberBudgetCouponIds(NOW, 0, Limit.of(100)));
        var members = captureRepositorySql(() -> memberCouponRepository.findExpiredPlatformBudgetTargets(platform, NOW, null, 0, Limit.of(1)));
        var after = captureRepositorySql(() -> memberCouponRepository.findExpiredPlatformBudgetTargets(platform, NOW, earlierExpiry, olderId, Limit.of(1)));
        var tail = captureRepositorySql(() -> memberCouponRepository.findExpiredPlatformBudgetTargets(platform, NOW, NOW, newerId, Limit.of(1)));
        assertThat(policies.result()).containsExactly(platform);
        assertThat(members.result()).extracting(MemberCouponExpiryTarget::id).containsExactly(olderId);
        assertThat(after.result()).extracting(MemberCouponExpiryTarget::id).containsExactly(newerId);
        assertThat(tail.result()).isEmpty();
        var duePolicies = explain("due-policies", policies, 0L, java.sql.Timestamp.from(NOW), 100);
        var dueMembers = explain("first-members", members, platform, java.sql.Timestamp.from(NOW), 1);
        var afterMembers = explain("after-members", after, platform, java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(earlierExpiry), olderId, 1);
        var tailMembers = explain("after-tail", tail, platform, java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW), newerId, 1);
        memberCouponRepository.planBudgetRelease(olderId, 0, MemberCouponStatus.EXPIRED, 0, 1_000, NOW);
        memberCouponRepository.planBudgetRelease(newerId, 0, MemberCouponStatus.EXPIRED, 0, 1_000, NOW);
        var emptyPolicies = captureRepositorySql(() -> couponRepository.findPlatformMemberBudgetCouponIds(NOW, 0, Limit.of(100)));
        var emptyMembers = captureRepositorySql(() -> memberCouponRepository.findExpiredPlatformBudgetTargets(platform, NOW, null, 0, Limit.of(100)));
        assertThat(emptyPolicies.result()).isEmpty();
        assertThat(emptyMembers.result()).isEmpty();
        var noPolicies = explain("no-due-policies", emptyPolicies, 0L, java.sql.Timestamp.from(NOW), 100);
        var noMembers = explain("no-due-members", emptyMembers, platform, java.sql.Timestamp.from(NOW), 100);
        writePlans("budget-release-future-used-query-plan.json", Map.of("futureUsedRows", futureUsedCount,
                "sellerExpiredRows", sellerExpiredCount,
                "dueRowsBeforeCompletion", 2, "dueRowsAfterCompletion", 0),
                duePolicies, dueMembers, afterMembers, tailMembers, noPolicies, noMembers);
        assertBoundedPlan(duePolicies, 1);
        assertBoundedPlan(dueMembers, 1);
        assertBoundedPlan(afterMembers, 1);
        assertBoundedPlan(tailMembers, 0);
        assertBoundedPlan(noPolicies, 0);
        assertBoundedPlan(noMembers, 0);
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
        assertThat(memberCouponRepository.findExpiredPlatformBudgetTargets(pendingA, NOW, null, 0, Limit.of(10)))
                .extracting(MemberCouponExpiryTarget::id).containsExactly(first);
        assertThat(memberCouponRepository.findExpiredPlatformBudgetTargets(pendingB, NOW, null, 0, Limit.of(10)))
                .extracting(MemberCouponExpiryTarget::id).containsExactly(second);
        assertThat(memberCouponRepository.findExpiredPlatformBudgetTargets(pendingA, NOW, NOW, first, Limit.of(10))).isEmpty();
        memberCouponRepository.planBudgetRelease(first, 0, MemberCouponStatus.EXPIRED, 0, 1_000, NOW);
        memberCouponRepository.planBudgetRelease(second, 0, MemberCouponStatus.USED, 0, 0, NOW);
        assertThat(couponRepository.findPlatformMemberBudgetCouponIds(NOW, 0, Limit.of(10))).isEmpty();
    }

    /** 기존 Logback 캡처 패턴을 사용하며 테스트 스레드의 실제 Repository SQL만 모은다. */
    private static <T> CapturedQuery<T> captureRepositorySql(Supplier<T> query) {
        Logger logger = (Logger) LoggerFactory.getLogger("org.hibernate.SQL");
        Level previousLevel = logger.getLevel();
        boolean previousAdditivity = logger.isAdditive();
        String testThread = Thread.currentThread().getName();
        ListAppender<ILoggingEvent> appender = new ListAppender<>() {
            @Override
            protected void append(ILoggingEvent event) {
                if (testThread.equals(event.getThreadName())) super.append(event);
            }
        };
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        logger.setAdditive(false);
        T result;
        try {
            result = query.get();
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            logger.setAdditive(previousAdditivity);
            appender.stop();
        }
        assertThat(appender.list).as("Repository가 실행한 SQL 한 건").hasSize(1);
        return new CapturedQuery<>(result, appender.list.getFirst().getFormattedMessage());
    }

    private ExplainedQuery explain(String name, CapturedQuery<?> query, Object... parameters) {
        String json = jdbc.queryForObject("explain (analyze, buffers, format json) " + query.sql(), String.class, parameters);
        return new ExplainedQuery(name, query.sql(), java.util.Arrays.stream(parameters).map(Object::toString).toList(),
                JsonMapper.builder().build().readTree(json));
    }

    /** 시간 대신 고정 로컬 fixture의 읽기 범위를 검증한다. 32 blocks는 운영 설정/처리량 기준이 아니다. */
    private static void assertBoundedPlan(ExplainedQuery query, long expectedRows) {
        JsonNode root = query.plan().get(0).get("Plan");
        for (String field : List.of("Actual Rows", "Shared Hit Blocks", "Shared Read Blocks")) {
            assertThat(root.get(field)).as(query.name() + " " + field + " 존재").isNotNull();
            assertThat(root.get(field).isNumber()).as(query.name() + " " + field + " 숫자").isTrue();
        }
        assertThat(root.get("Actual Rows").asDouble()).as(query.name() + " actual rows (바인딩 검증)").isEqualTo(expectedRows);
        long blocks = root.path("Shared Hit Blocks").asLong() + root.path("Shared Read Blocks").asLong();
        assertThat(blocks).as(query.name() + " shared hit + read blocks").isLessThan(32);
        List<JsonNode> nodes = new ArrayList<>();
        collectPlanNodes(root, nodes);
        assertThat(nodes).as(query.name() + " member_coupon full scan 없음")
                .noneMatch(node -> "member_coupon".equals(node.path("Relation Name").asText())
                        && "Seq Scan".equals(node.path("Node Type").asText()));
    }

    private static void collectPlanNodes(JsonNode node, List<JsonNode> result) {
        result.add(node);
        for (JsonNode child : node.path("Plans")) collectPlanNodes(child, result);
    }

    private static void writePlans(String filename, Map<String, Integer> fixture, ExplainedQuery... plans) throws Exception {
        Path artifact = Path.of("build", "reports", filename);
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, JsonMapper.builder().build().writeValueAsString(Map.of("fixture", fixture, "queries", plans)));
    }

    private record CapturedQuery<T>(T result, String sql) { }
    private record ExplainedQuery(String name, String sql, List<String> parameters, JsonNode plan) { }

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
