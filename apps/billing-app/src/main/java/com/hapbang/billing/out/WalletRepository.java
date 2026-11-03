package com.hapbang.billing.out;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.domain.WalletType;

import jakarta.persistence.LockModeType;

// 지갑 정보의 저장·조회를 담당하며 금융 데이터 삭제 메서드는 제공하지 않는다.
public interface WalletRepository extends Repository<Wallet, Long> {

    // 지갑 정보를 저장한다.
    Wallet save(Wallet entity);

    // 기본키 ID로 지갑 정보를 조회한다.
    Optional<Wallet> findById(Long id);

    // 회원 ID에 해당하는 지갑을 조회한다. 플랫폼 지갑은 회원 ID가 없어 찾지 못한다.
    Optional<Wallet> findByUserId(Long userId);

    // 유형으로 지갑을 조회한다. 플랫폼 지갑(PLATFORM)은 부분 유일 인덱스로 하나뿐이다 (#336).
    Optional<Wallet> findByWalletType(WalletType walletType);

    // 잔액 변경을 위해 기본키 ID로 지갑을 조회하며 트랜잭션이 끝날 때까지 행에 쓰기 락(SELECT ... FOR UPDATE)을 건다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.id = :id")
    Optional<Wallet> findByIdForUpdate(@Param("id") Long id);

    // 지갑 ID로 회원 ID를 찾는다 (#577, 서비스 간 HP 대사의 보류한 회원)
    List<Wallet> findAllByIdIn(Collection<Long> ids);

    // 기준 시각 이후 캐시 잔액 등이 바뀐 지갑 ID를 찾는다 (#607, 잔액 대사 대상). idx_wallet_1(updated_at)을 탄다
    @Query("select w.id from Wallet w where w.updatedAt >= :since")
    List<Long> findIdsUpdatedSince(@Param("since") Instant since);

    // 탈퇴로 닫혔는데 쓸 수 있는 충전·수익 HP가 남은 지갑 ID (#623, BD-141). 닫힌 뒤 들어온 반환·지급은 환불·환전할 수 없어 묶인다
    @Query("""
            select w.id from Wallet w
            where w.closedByWithdrawalId is not null and (w.chargeBalanceHp > 0 or w.revenueBalanceHp > 0)
            order by w.id""")
    List<Long> findClosedIdsWithBalance();
}
