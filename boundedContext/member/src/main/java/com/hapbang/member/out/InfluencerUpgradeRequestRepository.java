package com.hapbang.member.out;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.member.domain.InfluencerUpgradeRequest;
import com.hapbang.member.domain.UpgradeRequestStatus;

public interface InfluencerUpgradeRequestRepository extends JpaRepository<InfluencerUpgradeRequest, Long> {

    /** 이 회원의 심사 대기 신청이 있는지 (평소의 중복 확인) */
    boolean existsByUserIdAndStatus(Long userId, UpgradeRequestStatus status);

    /** 내 신청 내역 (최신순) */
    List<InfluencerUpgradeRequest> findByUserIdOrderByIdDesc(Long userId);
}
