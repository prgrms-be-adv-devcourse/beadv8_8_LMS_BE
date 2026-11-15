package com.hapbang.coupon.app;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.hapbang.coupon.app.dto.CouponSummaryView;
import com.hapbang.coupon.app.port.CouponIssueStore;
import com.hapbang.coupon.app.port.CouponIssueStore.IssueSnapshot;
import com.hapbang.coupon.app.port.CouponIssueStoreUnavailableException;
import com.hapbang.coupon.config.CouponClockConfig;
import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponAvailabilityRule;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.global.exception.CouponException;
import com.hapbang.coupon.out.CouponRepository;
import com.hapbang.coupon.out.MemberCouponRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 회원 쿠폰 조회: 강의 화면의 받을 수 있는 쿠폰 목록과 쿠폰 상세. 매진·받음 표시는 DB(발급 수·발급 쿠폰)와 Redis(남은 재고·접수 회원)를
 * 함께 보고 {@link CouponAvailabilityRule}이 정한다. Redis는 한 번의 파이프라인으로 읽고, 닿지 못하면 DB 값만으로 표시한다(조회는
 * 실패시키지 않는다).
 */
@Slf4j
@Service
public class CouponSummaryQuery {

    private final CouponRepository couponRepository;
    private final MemberCouponRepository memberCouponRepository;
    private final CouponIssueStore couponIssueStore;
    private final CouponSupport couponSupport;
    private final Clock clock;

    public CouponSummaryQuery(CouponRepository couponRepository, MemberCouponRepository memberCouponRepository,
                              CouponIssueStore couponIssueStore, CouponSupport couponSupport,
                              @Qualifier(CouponClockConfig.CLOCK) Clock clock) {
        this.couponRepository = couponRepository;
        this.memberCouponRepository = memberCouponRepository;
        this.couponIssueStore = couponIssueStore;
        this.couponSupport = couponSupport;
        this.clock = clock;
    }

    /** 이 강의에 지금 받을 수 있는 쿠폰(최신순, 상한 {@link CouponSupport#MAX_LIST_SIZE}건). 매진·이미 받은 쿠폰도 표시와 함께 넣는다 */
    public List<CouponSummaryView> issuableFor(long memberId, long lectureId) {
        Instant now = clock.instant();
        List<Coupon> coupons = couponRepository.findIssuableFor(lectureId, now, CouponSupport.LIST_LIMIT).stream()
                .filter(coupon -> CouponAvailabilityRule.isListedFor(coupon, lectureId, now))
                .toList();
        return toViews(memberId, coupons);
    }

    /** 쿠폰 상세. 회원이 볼 수 없는 상태(DRAFT·REVIEW·REJECTED)는 없는 쿠폰과 같이 404다 */
    public CouponSummaryView summary(long memberId, long couponId) {
        Coupon coupon = couponSupport.findCoupon(couponId);
        if (!CouponAvailabilityRule.isVisibleToMember(coupon.getStatus())) {
            throw new CouponException(CouponErrorCode.COUPON_NOT_FOUND);
        }
        return toViews(memberId, List.of(coupon)).getFirst();
    }

    private List<CouponSummaryView> toViews(long memberId, List<Coupon> coupons) {
        if (coupons.isEmpty()) {
            return List.of();
        }
        List<Long> couponIds = coupons.stream().map(Coupon::getId).toList();
        Set<Long> owned = new HashSet<>(memberCouponRepository.findCouponIdsOwnedBy(memberId, couponIds));
        Map<Long, IssueSnapshot> snapshots = readSnapshots(couponIds, memberId);
        return coupons.stream()
                .map(coupon -> {
                    IssueSnapshot snapshot = snapshots.get(coupon.getId());
                    Long stock = snapshot == null ? null : snapshot.stock();
                    boolean accepted = snapshot != null && snapshot.memberAccepted();
                    return CouponSummaryView.of(coupon,
                            CouponAvailabilityRule.isSoldOut(coupon.getQuantity(), coupon.getIssuedCount(), stock),
                            CouponAvailabilityRule.isIssued(owned.contains(coupon.getId()), accepted));
                })
                .toList();
    }

    /** Redis 표시 값. 닿지 못하면 빈 값(모름)이고 DB 값만으로 표시한다 */
    private Map<Long, IssueSnapshot> readSnapshots(List<Long> couponIds, long memberId) {
        try {
            return couponIssueStore.readSnapshots(couponIds, memberId);
        } catch (CouponIssueStoreUnavailableException exception) {
            // Redis 장애 동안 조회마다 몰리므로 요청마다 WARN을 남기지 않는다. 장애는 발급 접수의 503으로 보인다
            log.debug("발급 표시 값을 읽지 못해 DB 값만으로 응답한다 couponCount={}, memberId={}", couponIds.size(), memberId,
                    exception);
            return Map.of();
        }
    }
}
