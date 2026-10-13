package com.hapbang.billing.out;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.hapbang.billing.domain.PointLedger;

// HP 원장 항목 정보의 저장·조회를 담당하며 금융 데이터 삭제 메서드는 제공하지 않는다.
public interface PointLedgerRepository extends Repository<PointLedger, Long> {

    // HP 원장 항목 정보를 저장한다.
    PointLedger save(PointLedger entity);

    // 기본키 ID로 HP 원장 항목 정보를 조회한다.
    Optional<PointLedger> findById(Long id);

    // 멱등키로 기존 HP 원장 항목 정보를 조회한다.
    Optional<PointLedger> findByIdempotencyKey(String idempotencyKey);

    // 같은 비즈니스 작업에 속한 원장 항목을 ID 오름차순으로 조회한다.
    List<PointLedger> findAllByOperationKeyOrderByIdAsc(String operationKey);

    // 지갑의 원장 증감을 HP 종류·잔액 버킷별로 합산한다. 원장이 없는 버킷은 결과에 포함되지 않는다.
    @Query("""
            select new com.hapbang.billing.out.LedgerBucketSum(l.pointType, l.balanceBucket, sum(l.amountHp))
            from PointLedger l
            where l.walletId = :walletId
            group by l.pointType, l.balanceBucket""")
    List<LedgerBucketSum> sumAmountByWalletId(@Param("walletId") Long walletId);

    // 원본 원장 항목을 참조하는 역분개 원장 금액의 누적 합계를 구하며, 역분개가 없으면 0이다.
    @Query("select coalesce(sum(l.amountHp), 0) from PointLedger l where l.originalLedgerId = :originalLedgerId")
    long sumAmountByOriginalLedgerId(@Param("originalLedgerId") Long originalLedgerId);
}
