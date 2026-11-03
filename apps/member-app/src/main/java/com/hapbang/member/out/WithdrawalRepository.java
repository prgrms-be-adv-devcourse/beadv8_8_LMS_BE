package com.hapbang.member.out;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.member.domain.Withdrawal;
import com.hapbang.member.domain.WithdrawalStatus;

/** 회원 탈퇴 작업 기록 저장소 (#593) */
public interface WithdrawalRepository extends JpaRepository<Withdrawal, Long> {

    /** 회원의 가장 최근 작업 중 이 상태인 것 (복구 때 지갑을 닫은 완료 작업을 찾는다) */
    Optional<Withdrawal> findFirstByUserIdAndStatusOrderByIdDesc(Long userId, WithdrawalStatus status);

    /** 회원의 진행 중인 작업 (부분 유일 인덱스로 회원당 하나뿐이다) */
    Optional<Withdrawal> findFirstByUserIdAndStatusIn(Long userId, Collection<WithdrawalStatus> statuses);

    /**
     * 배치가 정리할 작업 ID (오래된 순): 다시 열기에 실패한 작업, 진행 중인데 {@code staleBefore} 전부터 바뀌지 않은 작업.
     * 진행 중 상태만 담는 부분 인덱스 범위라 평소에는 대상이 없어 가볍다
     */
    @Query("""
            select w.id from Withdrawal w
            where w.status = com.hapbang.member.domain.WithdrawalStatus.NEEDS_RECOVERY
               or (w.status in :inProgress and w.updatedAt <= :staleBefore)
            order by w.id
            """)
    List<Long> findRecoveryTargetIds(@Param("inProgress") Collection<WithdrawalStatus> inProgress,
                                     @Param("staleBefore") LocalDateTime staleBefore, Pageable limit);
}
