package com.hapbang.member.in;

import com.hapbang.member.domain.InfluencerUpgradeRequest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 승급 신청 요청. 사유 필수·500자 이하 (도메인이 앞뒤 공백을 지우고 한 번 더 검사한다) */
public record UpgradeApplyRequest(
        @NotBlank(message = "승급 신청 사유를 입력해 주세요.")
        @Size(max = InfluencerUpgradeRequest.REASON_MAX, message = "승급 신청 사유는 500자 이하로 입력해 주세요.")
        String reason
) {
}
