package com.hapbang.coupon.out;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.coupon.domain.IssueCapacity;

/**
 * 발급 저장소 용량 예약 합계. 동시 발급 열기가 합산 상한을 넘지 않도록 합계를 조건부로만 바꾼다. 행이 없으면 합계 0이다.
 * 쿠폰별 예약 행({@link IssueCapacityReservationRepository})과 같은 트랜잭션에서 쓴다.
 */
public interface IssueCapacityRepository extends JpaRepository<IssueCapacity, Short> {

    /**
     * 합계에 수량을 더한다. 더한 합계가 상한 이하일 때만 반영한다(행이 없으면 수량으로 만든다). 같은 행의 동시 UPSERT는 행 잠금으로 줄을 서고,
     * 뒤에 온 쪽은 앞의 커밋을 반영한 합계로 조건을 다시 본다.
     *
     * @return 1 반영함, 0 상한을 넘어 반영하지 않음
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            insert into coupon.issue_capacity as capacity (id, reserved_total, updated_at)
            select 1, :quantity, :now
            where :quantity <= :limit
            on conflict (id) do update
                set reserved_total = capacity.reserved_total + excluded.reserved_total,
                    updated_at = excluded.updated_at
                where capacity.reserved_total + excluded.reserved_total <= :limit
            """)
    int reserve(@Param("quantity") long quantity, @Param("limit") long limit, @Param("now") Instant now);

    /**
     * 합계에서 수량을 뺀다. 합계가 수량 이상일 때만 반영한다(음수가 되지 않는다).
     *
     * @return 1 반영함, 0 합계가 수량보다 작거나 행이 없어 반영하지 않음(예약 기록이 어긋났다)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update IssueCapacity c
            set c.reservedTotal = c.reservedTotal - :quantity, c.updatedAt = :now
            where c.id = 1 and c.reservedTotal >= :quantity
            """)
    int release(@Param("quantity") long quantity, @Param("now") Instant now);

    /** 지금 예약 합계. 행이 없으면 0 */
    default long reservedTotal() {
        return findById(IssueCapacity.SINGLE_ROW_ID).map(IssueCapacity::getReservedTotal).orElse(0L);
    }
}
