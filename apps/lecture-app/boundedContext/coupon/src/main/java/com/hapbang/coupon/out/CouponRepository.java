package com.hapbang.coupon.out;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * 확정 발급 수를 "확정 발급 수 &lt; 총수량"이고 쿠폰의 epoch가 요청의 epoch와 같을 때만 1 올린다(발급 확정 트랜잭션의 마지막). 바꾼 행 수
     * (1 또는 0)를 돌려주고 해석은 {@code IssueCountRule.interpret}가 한다. 쿠폰 행을 잠그므로 같은 쿠폰의 확정은 순서대로 커밋된다.
     * <p>
     * epoch 조건은 소비자가 쿠폰을 읽은 뒤 복구 재개가 새 epoch를 커밋한 경우를 막는다. 같은 트랜잭션에서 읽었다는 사실만으로는 동시 epoch
     * 변경을 막지 못하므로, 쿠폰 행을 잠그는 이 UPDATE가 epoch를 다시 확인한다(재개의 epoch 변경이 먼저 커밋되면 잠금을 얻은 뒤 바뀐 행으로
     * 조건을 다시 평가해 0행이다). 0행이면 호출자가 쿠폰을 다시 읽어 epoch 변경인지 총량 초과인지 가린다.
     * <p>
     * {@code version}은 올리지 않는다(발급 확정이 관리자 상태 변경의 낙관적 락·Redis fence를 흔들지 않게). 그래서 엔티티 UPDATE는
     * {@code issued_count}를 쓰지 않는다({@code Coupon.issuedCount}의 updatable = false). 엔티티가 이 컬럼을 쓰지 않으므로 native로 둔다.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            update coupon.coupon set issued_count = issued_count + 1
            where id = :couponId and issued_count < quantity and epoch = :epoch
            """, nativeQuery = true)
    int incrementIssuedCount(@Param("couponId") long couponId, @Param("epoch") long epoch);
}
