package com.hapbang.member.app;

import com.hapbang.member.domain.ChannelVerificationStatus;
import com.hapbang.shared.member.domain.Role;

/** 채널 연동 결과. role은 연동 후 회원 역할 (자동 승인이면 INFLUENCER) → 세션 권한 갱신에 쓴다 */
public record ChannelConnectResult(ChannelVerificationStatus status, Role role) {

    public boolean isApproved() {
        return status == ChannelVerificationStatus.APPROVED;
    }
}
