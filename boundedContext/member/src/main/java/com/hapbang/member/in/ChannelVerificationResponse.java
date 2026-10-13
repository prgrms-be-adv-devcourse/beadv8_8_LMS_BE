package com.hapbang.member.in;

import com.hapbang.member.app.ChannelVerificationResult;
import com.hapbang.member.domain.ChannelVerificationStatus;
import com.hapbang.shared.member.domain.Role;

/** 채널 승인 요청 결과. status: APPROVED(자동 승인) / PENDING(관리자 심사 대기), role: 처리 후 내 역할 */
public record ChannelVerificationResponse(ChannelVerificationStatus status, Role role) {

    public static ChannelVerificationResponse from(ChannelVerificationResult result) {
        return new ChannelVerificationResponse(result.status(), result.role());
    }
}
