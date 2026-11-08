package com.hapbang.coupon.out;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import com.hapbang.coupon.domain.Coupon;

import jakarta.persistence.LockModeType;

/** 쿠폰 정책 저장소. 동시 상태 변경은 {@code Coupon}의 {@code @Version}이 막는다 */
public interface CouponRepository extends JpaRepository<Coupon, Long> {

    /**
     * 쿠폰을 읽고, 상태를 바꾸지 않아도 이 트랜잭션이 커밋될 때 {@code version}을 올린다(OPTIMISTIC_FORCE_INCREMENT).
     * 커밋된 version을 발급 저장소의 fence로 새로 쓸 때(발급 열기·재개) 트랜잭션의 첫 조회로 쓴다.
     */
    @Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
    Optional<Coupon> findWithVersionIncrementById(Long id);
}
