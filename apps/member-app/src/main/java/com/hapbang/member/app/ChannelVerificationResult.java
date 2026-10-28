package com.hapbang.member.app;

import com.hapbang.member.domain.ChannelVerificationStatus;
import com.hapbang.shared.member.domain.Role;

/**
 * 채널 연동·승인 요청 결과.
 * role은 처리 후 회원 역할, roleChanged는 이번에 유저 → 인플루언서로 바뀌었는지 (바뀌었을 때만 세션 권한을 갱신한다)
 */
public record ChannelVerificationResult(ChannelVerificationStatus status, Role role, boolean roleChanged) {

    public boolean isApproved() {
        return status == ChannelVerificationStatus.APPROVED;
    }
}
