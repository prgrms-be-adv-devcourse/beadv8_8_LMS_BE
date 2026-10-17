package com.hapbang.billing.out;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    // 지갑의 원장을 업무 작업(wallet_id + operation_key)마다 묶어 HP 종류·잔액 버킷별 증감을 합하고, 작업의 첫 원장 ID 내림차순으로
    // 페이지를 나눈다 (#295). 한 작업의 거래 유형·참조 대상·발생 시각은 같으므로 함께 묶어도 작업 하나가 한 줄이다.
    // 다른 지갑의 원장(지급의 받는 쪽 등)은 같은 작업 키여도 넣지 않는다. 전체 건수는 지갑의 서로 다른 작업 수다.
    @Query(value = """
            select new com.hapbang.billing.out.LedgerOperationSum(
                min(l.id), l.operationKey, l.transactionType, l.referenceType, l.referenceId, l.occurredAt,
                sum(case when l.pointType = com.hapbang.billing.domain.PointType.CHARGE
                         and l.balanceBucket = com.hapbang.billing.domain.BalanceBucket.AVAILABLE then l.amountHp else 0L end),
                sum(case when l.pointType = com.hapbang.billing.domain.PointType.CHARGE
                         and l.balanceBucket = com.hapbang.billing.domain.BalanceBucket.HELD then l.amountHp else 0L end),
                sum(case when l.pointType = com.hapbang.billing.domain.PointType.REVENUE
                         and l.balanceBucket = com.hapbang.billing.domain.BalanceBucket.AVAILABLE then l.amountHp else 0L end),
                sum(case when l.pointType = com.hapbang.billing.domain.PointType.REVENUE
                         and l.balanceBucket = com.hapbang.billing.domain.BalanceBucket.HELD then l.amountHp else 0L end))
            from PointLedger l
            where l.walletId = :walletId
            group by l.operationKey, l.transactionType, l.referenceType, l.referenceId, l.occurredAt
            order by min(l.id) desc""",
            countQuery = "select count(distinct l.operationKey) from PointLedger l where l.walletId = :walletId")
    Page<LedgerOperationSum> findOperationSumsByWalletId(@Param("walletId") Long walletId, Pageable pageable);

    // 원본 원장 항목을 참조하는 역분개 원장 금액의 누적 합계를 구하며, 역분개가 없으면 0이다.
    @Query("select coalesce(sum(l.amountHp), 0) from PointLedger l where l.originalLedgerId = :originalLedgerId")
    long sumAmountByOriginalLedgerId(@Param("originalLedgerId") Long originalLedgerId);
}
