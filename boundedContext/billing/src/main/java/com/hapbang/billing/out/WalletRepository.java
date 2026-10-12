package com.hapbang.billing.out;

import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.hapbang.billing.domain.Wallet;

// 지갑 정보의 저장·조회를 담당하며 금융 데이터 삭제 메서드는 제공하지 않는다.
public interface WalletRepository extends Repository<Wallet, Long> {

    // 지갑 정보를 저장한다.
    Wallet save(Wallet entity);

    // 기본키 ID로 지갑 정보를 조회한다.
    Optional<Wallet> findById(Long id);

    // 회원 ID에 해당하는 지갑을 조회한다.
    Optional<Wallet> findByUserId(Long userId);
}
