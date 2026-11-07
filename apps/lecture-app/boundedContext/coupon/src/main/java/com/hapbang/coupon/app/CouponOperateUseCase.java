package com.hapbang.coupon.app;

import java.time.Clock;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.coupon.app.dto.CouponView;
import com.hapbang.coupon.app.port.CouponIssueStore;
import com.hapbang.coupon.config.CouponClockConfig;
import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponStatus;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.global.exception.CouponException;

import lombok.extern.slf4j.Slf4j;

/**
 * 발급 운영: 중단(OPEN → SUSPENDED), 재개(SUSPENDED → OPEN), 종료(OPEN·SUSPENDED → CLOSED). Redis 호출은 트랜잭션 밖이다.
 * <ul>
 *     <li>중단·종료: 지금 DB version v로 Redis를 막고 확인 → 같은 트랜잭션에서 DB version이 아직 v인지 확인하고 상태 변경(커밋) →
 *     커밋된 version으로 Redis를 한 번 더 막는다({@link CouponSupport}의 fence 규칙). 막은 뒤 그 사이 재개·열기가 version을 올렸으면
 *     DB를 바꾸지 않고 새 version으로 다시 막는다(그러지 않으면 두 번째 막기 전에 멈췄을 때 DB만 막히고 Redis는 열린 채 남는다).
 *     Redis를 막지 못하면 DB는 그대로이고 503이다. 이미 그 상태인 쿠폰을 다시 요청하면 Redis만 다시 막고 성공한다.</li>
 *     <li>종료의 첫 막기는 SUSPENDED로 쓴다. Redis의 CLOSED는 되돌릴 수 없어 DB 종료가 커밋된 뒤에만 쓴다(DB 종료가 실패해도 재개로
 *     되돌릴 수 있게).</li>
 *     <li>재개: DB를 OPEN으로 커밋(이미 OPEN이면 version만 올린다)한 뒤 커밋된 version으로 Redis 접수를 연다
 *     ({@link CouponSupport#allowIssue}). Redis를 열지 못하면 DB는 OPEN, Redis는 막힌 채(접수 안 됨)로 503이고, 다시 요청하면 version을
 *     올려 다시 연다. 그 사이 중단·종료가 더 새 version으로 막았으면 Redis는 막힌 채로 409다.</li>
 *     <li>종료해도 발급되지 않은 수량의 예산은 여기서 해제하지 않는다(진행 중 발급을 정리한 뒤 따로 해제한다).</li>
 * </ul>
 */
@Slf4j
@Service
public class CouponOperateUseCase {

    /** 막은 뒤 version이 바뀌어 다시 막는 횟수 상한. 계속 겹치면 409로 끝내고 호출자가 다시 요청한다 */
    private static final int MAX_BLOCK_ATTEMPTS = 3;

    private final CouponSupport couponSupport;
    private final CouponIssueStore couponIssueStore;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public CouponOperateUseCase(CouponSupport couponSupport, CouponIssueStore couponIssueStore,
                                PlatformTransactionManager transactionManager,
                                @Qualifier(CouponClockConfig.CLOCK) Clock clock) {
        this.couponSupport = couponSupport;
        this.couponIssueStore = couponIssueStore;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** 관리자 중단 */
    public CouponView suspend(long adminId, long couponId) {
        CouponView view = block(couponId, null, CouponSupport.REDIS_SUSPENDED, CouponSupport.REDIS_SUSPENDED,
                CouponStatus.SUSPENDED, coupon -> coupon.suspend(clock.instant()), CouponStatus.OPEN);
        log.info("쿠폰 발급을 중단했다 couponId={}, adminId={}", couponId, adminId);
        return view;
    }

    /** 판매자 중단. 신청한 판매자 본인만 */
    public CouponView suspendBySeller(long sellerId, long couponId) {
        CouponView view = block(couponId, sellerId, CouponSupport.REDIS_SUSPENDED, CouponSupport.REDIS_SUSPENDED,
                CouponStatus.SUSPENDED, coupon -> coupon.suspend(clock.instant()), CouponStatus.OPEN);
        log.info("판매자가 쿠폰 발급을 중단했다 couponId={}, sellerId={}", couponId, sellerId);
        return view;
    }

    public CouponView close(long adminId, long couponId) {
        CouponView view = block(couponId, null, CouponSupport.REDIS_SUSPENDED, CouponSupport.REDIS_CLOSED,
                CouponStatus.CLOSED, coupon -> coupon.close(clock.instant()), CouponStatus.OPEN, CouponStatus.SUSPENDED);
        log.info("쿠폰 발급을 종료했다 couponId={}, adminId={}", couponId, adminId);
        return view;
    }

    public CouponView resume(long adminId, long couponId) {
        Coupon resumed = transaction.execute(status -> {
            Coupon coupon = couponSupport.findCouponIncrementingVersion(couponId);
            if (coupon.getStatus() != CouponStatus.OPEN) {
                coupon.resume(clock.instant());
            }
            return coupon;
        });
        couponSupport.allowIssue(couponId, resumed.getVersion());
        log.info("쿠폰 발급을 재개했다 couponId={}, adminId={}", couponId, adminId);
        return CouponView.from(resumed);
    }

    /**
     * @param sellerId     판매자 요청이면 본인 확인, 관리자면 {@code null}
     * @param firstStatus  DB 커밋 전에 Redis에 쓸 차단 상태(되돌릴 수 있는 값)
     * @param finalStatus  DB 커밋 뒤 Redis에 쓸 차단 상태
     * @param target       DB 목표 상태. 이미 이 상태면 DB는 바꾸지 않는다
     * @param transition   DB 상태 전이(도메인)
     * @param from         전이할 수 있는 상태
     */
    private CouponView block(long couponId, Long sellerId, String firstStatus, String finalStatus, CouponStatus target,
                             Consumer<Coupon> transition, CouponStatus... from) {
        for (int attempt = 1; ; attempt++) {
            Coupon current = couponSupport.findCoupon(couponId);
            if (sellerId != null) {
                couponSupport.requireOwner(current, sellerId);
            }
            if (current.getStatus() != target) {
                couponSupport.requireStatus(current, from);
            }
            long blockedVersion = current.getVersion();
            couponIssueStore.block(couponId, firstStatus, blockedVersion);
            Coupon blocked = transaction.execute(status -> {
                Coupon coupon = couponSupport.findCoupon(couponId);
                if (coupon.getVersion() != blockedVersion) {
                    // 막은 뒤 다른 요청이 version을 올렸다(재개·열기 등). 그 요청이 Redis를 다시 열었을 수 있으므로 DB를 바꾸지 않는다
                    return null;
                }
                if (coupon.getStatus() != target) {
                    transition.accept(coupon);
                }
                return coupon;
            });
            if (blocked != null) {
                couponIssueStore.block(couponId, finalStatus, blocked.getVersion());
                return CouponView.from(blocked);
            }
            if (attempt == MAX_BLOCK_ATTEMPTS) {
                throw new CouponException(CouponErrorCode.INVALID_COUPON_STATUS, "다른 변경과 겹쳐 처리하지 못했습니다. 다시 시도해 주세요.");
            }
        }
    }
}
