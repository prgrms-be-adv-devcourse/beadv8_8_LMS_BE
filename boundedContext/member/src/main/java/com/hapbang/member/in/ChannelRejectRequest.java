package com.hapbang.member.in;

import com.hapbang.member.domain.InfluencerChannel;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 채널 반려 요청. 사유 필수·500자 이하 (도메인이 앞뒤 공백을 지우고 한 번 더 검사한다) */
public record ChannelRejectRequest(
        @NotBlank(message = "반려 사유를 입력해 주세요.")
        @Size(max = InfluencerChannel.REJECTION_REASON_MAX, message = "반려 사유는 500자 이하로 입력해 주세요.")
        String reason
) {
}
