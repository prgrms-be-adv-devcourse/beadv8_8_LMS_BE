package com.hapbang.billing.out;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

// 회원 한 명의 지갑 생성과 탈퇴용 닫기·다시 열기를 직렬화하는 트랜잭션 advisory lock (#623).
// 지갑이 아직 없는 회원은 잠글 지갑 행이 없어, 닫기 기록과 지갑 생성이 동시에 일어나면 지갑이 열린 채 남을 수 있다. 두 경로가 이 락을
// 먼저 잡으므로 지갑 생성은 먼저 커밋된 닫기 기록을 보고, 닫기는 먼저 커밋된 지갑을 본다. 락은 트랜잭션이 끝나면 풀린다.
// 두 int 키(pg_advisory_xact_lock(int, int))를 써서 배치 실행 락(BillingJob, bigint 키 하나)과 키 공간이 겹치지 않는다.
// 회원 ID를 int로 접어 쓰므로 다른 회원과 키가 겹칠 수 있지만, 그때는 잠깐 더 기다릴 뿐 결과는 같다. 락 순서는 이 락 → 지갑 락이다.
@Component
@RequiredArgsConstructor
public class WalletUserLock {

    /** 이 락의 키 공간 번호 (이슈 번호) */
    static final int NAMESPACE = 623;

    private final EntityManager entityManager;

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(long userId) {
        entityManager.createNativeQuery("select cast(pg_advisory_xact_lock(:namespace, :key) as text)")
                .setParameter("namespace", NAMESPACE)
                .setParameter("key", fold(userId))
                .getSingleResult();
    }

    static int fold(long userId) {
        return (int) (userId ^ (userId >>> 32));
    }
}
