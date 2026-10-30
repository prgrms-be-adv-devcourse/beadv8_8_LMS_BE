package com.hapbang.billing.out;

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
}
