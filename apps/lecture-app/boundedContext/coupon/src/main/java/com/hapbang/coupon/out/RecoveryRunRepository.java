package com.hapbang.coupon.out;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.coupon.domain.RecoveryRun;
import com.hapbang.coupon.domain.RecoveryStep;

/**
 * Redis 유실 복구 실행 저장소. 쿠폰마다 끝나지 않은(RESUMED·ABORTED가 아닌) 복구는 하나뿐이다(부분 유일 인덱스
 * {@code uk_recovery_run_active}). 동시에 두 복구를 시작하면 늦게 저장하는 쪽이 유일 제약 위반으로 실패한다.
 */
public interface RecoveryRunRepository extends JpaRepository<RecoveryRun, Long> {

    Optional<RecoveryRun> findByIdAndCouponId(Long id, Long couponId);

    /** 쿠폰의 복구 실행들. 최근 것부터 */
    List<RecoveryRun> findByCouponIdOrderByIdDesc(Long couponId);

    /** 쿠폰의 가장 최근 복구 실행 */
    Optional<RecoveryRun> findFirstByCouponIdOrderByIdDesc(Long couponId);

    /** 쿠폰의 끝나지 않은 복구. {@code steps}에는 {@link RecoveryStep#finished()}를 넘긴다 */
    Optional<RecoveryRun> findFirstByCouponIdAndStepNotIn(Long couponId, Collection<RecoveryStep> steps);

    /** 쿠폰에서 접수 기록 유실이 의심된 가장 최근 복구(단계 무관). 상태 조회의 UNKNOWN 판정에 쓴다 */
    Optional<RecoveryRun> findFirstByCouponIdAndRequestsLostTrueOrderByIdDesc(Long couponId);
}
