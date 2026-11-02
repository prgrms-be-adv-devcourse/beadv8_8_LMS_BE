package com.hapbang.member.app;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.hapbang.member.domain.InfluencerUpgradeRequest;
import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.UpgradeRequestStatus;
import com.hapbang.member.domain.User;
import com.hapbang.member.out.InfluencerUpgradeRequestRepository;

import lombok.RequiredArgsConstructor;

/** 승급 신청. 대기 중 1건은 앱에서 먼저 확인하고, 동시 신청은 DB 인덱스가 막은 것을 같은 409로 바꾼다. 트랜잭션은 Facade에 있다 */
@Service
@RequiredArgsConstructor
public class MemberUpgradeRequestUseCase {

    /** 회원당 심사 대기 1건 인덱스 이름 (마이그레이션과 같아야 한다) */
    static final String ONE_PENDING_INDEX = "uk_influencer_upgrade_request_one_pending";

    private final MemberSupport memberSupport;
    private final InfluencerUpgradeRequestRepository influencerUpgradeRequestRepository;

    /** 신청 (정상 회원·인플루언서만, 사유 필수, 심사 대기 중이면 409) */
    public InfluencerUpgradeRequest request(Long userId, String reason) {
        User user = memberSupport.findActiveUser(userId);
        if (influencerUpgradeRequestRepository.existsByUserIdAndStatus(userId, UpgradeRequestStatus.PENDING)) {
            throw new MemberException(MemberErrorCode.UPGRADE_REQUEST_ALREADY_PENDING);
        }
        InfluencerUpgradeRequest request = InfluencerUpgradeRequest.request(user, reason, LocalDateTime.now());
        try {
            // 바로 INSERT해서 동시 신청의 인덱스 위반을 여기서 받는다 (save면 커밋 때 터져 409로 바꿀 수 없다)
            return influencerUpgradeRequestRepository.saveAndFlush(request);
        } catch (DataIntegrityViolationException e) {
            if (isOnePendingViolation(e)) {
                throw new MemberException(MemberErrorCode.UPGRADE_REQUEST_ALREADY_PENDING, e);
            }
            throw e;
        }
    }

    /** 내 신청 내역 (최신순) */
    public List<InfluencerUpgradeRequest> findMine(Long userId) {
        return influencerUpgradeRequestRepository.findByUserIdOrderByIdDesc(userId);
    }

    /** 우리 인덱스 위반일 때만 409 (회원 FK 등 다른 제약 위반은 예상 못 한 실패라 그대로 500) */
    private boolean isOnePendingViolation(DataIntegrityViolationException e) {
        String message = NestedExceptionUtils.getMostSpecificCause(e).getMessage();
        return message != null && message.contains(ONE_PENDING_INDEX);
    }
}
