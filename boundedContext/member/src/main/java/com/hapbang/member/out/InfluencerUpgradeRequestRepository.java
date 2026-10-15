package com.hapbang.member.out;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.member.domain.InfluencerUpgradeRequest;
import com.hapbang.member.domain.UpgradeRequestStatus;
import com.hapbang.shared.member.domain.UserStatus;

import jakarta.persistence.LockModeType;

public interface InfluencerUpgradeRequestRepository extends JpaRepository<InfluencerUpgradeRequest, Long> {

    /** 이 회원의 심사 대기 신청이 있는지 (평소의 중복 확인) */
    boolean existsByUserIdAndStatus(Long userId, UpgradeRequestStatus status);

    /** 내 신청 내역 (최신순) */
    List<InfluencerUpgradeRequest> findByUserIdOrderByIdDesc(Long userId);

    /** 심사 목록: 상태별, 해당 상태의 회원만, 회원(닉네임)을 함께 읽는다 */
    @EntityGraph(attributePaths = "user")
    Page<InfluencerUpgradeRequest> findByStatusAndUserStatus(UpgradeRequestStatus status, UserStatus userStatus,
                                                             Pageable pageable);

    /** 승인·반려 처리용 조회: 행을 잠가(FOR UPDATE) 관리자 동시 처리를 막는다 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from InfluencerUpgradeRequest r where r.id = :id")
    Optional<InfluencerUpgradeRequest> findByIdForUpdate(@Param("id") Long id);
}
