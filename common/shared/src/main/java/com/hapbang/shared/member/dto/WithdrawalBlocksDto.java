package com.hapbang.shared.member.dto;

import java.util.List;
import java.util.Objects;

/**
 * 출연·강의의 탈퇴 막힌 이유 조회 응답 (#593, docs/api/internal-withdrawals.md 3장).
 *
 * @param reasons 막힌 이유. 없으면 빈 목록
 */
public record WithdrawalBlocksDto(List<WithdrawalBlockReasonDto> reasons) {

    // 빠진 응답은 Client가 읽을 수 없는 응답으로 거절한다. null 항목도 받지 않는다
    public WithdrawalBlocksDto {
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons는 필수입니다."));
    }

    /** 막힌 이유가 없음 */
    public static WithdrawalBlocksDto none() {
        return new WithdrawalBlocksDto(List.of());
    }

    public boolean isBlocked() {
        return !reasons.isEmpty();
    }
}
