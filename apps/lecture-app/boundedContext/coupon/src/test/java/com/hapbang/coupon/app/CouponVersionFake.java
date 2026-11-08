package com.hapbang.coupon.app;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponStatus;
import com.hapbang.coupon.out.CouponRepository;

/**
 * 쿠폰 하나의 DB 대역. 저장소 mock은 같은 객체를 돌려주고, 트랜잭션 대역은 커밋 때 쿠폰이 바뀌었거나 version 강제 증가 조회
 * ({@link CouponRepository#findWithVersionIncrementById})가 있었으면 {@code @Version}처럼 version을 1 올린다. 격리는 흉내 내지 않는다.
 */
final class CouponVersionFake {

    private final CouponRepository couponRepository;
    private final PlatformTransactionManager transactionManager = mock();
    private final Deque<Snapshot> openTransactions = new ArrayDeque<>();
    private Coupon coupon;
    private boolean forceIncrement;

    CouponVersionFake(CouponRepository couponRepository) {
        this.couponRepository = couponRepository;
        when(transactionManager.getTransaction(any())).thenAnswer(invocation -> {
            openTransactions.push(Snapshot.of(coupon));
            forceIncrement = false;
            return new SimpleTransactionStatus();
        });
        doAnswer(invocation -> {
            Snapshot before = openTransactions.pop();
            if (coupon != null && (forceIncrement || !before.equals(Snapshot.of(coupon)))) {
                ReflectionTestUtils.setField(coupon, "version", coupon.getVersion() + 1);
            }
            forceIncrement = false;
            return null;
        }).when(transactionManager).commit(any());
        doAnswer(invocation -> {
            openTransactions.pop();
            forceIncrement = false;
            return null;
        }).when(transactionManager).rollback(any());
    }

    void given(Coupon value) {
        coupon = value;
        when(couponRepository.findById(coupon.getId())).thenReturn(Optional.of(coupon));
        when(couponRepository.findWithVersionIncrementById(coupon.getId())).thenAnswer(invocation -> {
            forceIncrement = true;
            return Optional.of(coupon);
        });
    }

    PlatformTransactionManager transactionManager() {
        return transactionManager;
    }

    /** 커밋 때 version을 올릴지 판단하는 쿠폰 값 */
    private record Snapshot(CouponStatus status, boolean redisReady, String budgetReservationId) {

        static Snapshot of(Coupon coupon) {
            return coupon == null ? new Snapshot(null, false, null)
                    : new Snapshot(coupon.getStatus(), coupon.isRedisReady(), coupon.getBudgetReservationId());
        }
    }
}
