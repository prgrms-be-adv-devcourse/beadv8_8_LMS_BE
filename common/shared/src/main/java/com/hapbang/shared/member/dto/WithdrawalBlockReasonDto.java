package com.hapbang.shared.member.dto;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 회원 탈퇴가 막힌 이유 한 줄 (#593, docs/api/internal-withdrawals.md 2장). 지갑·출연·강의가 내부 API로 돌려주고,
 * 회원이 모아서 탈퇴 409 응답의 reasons로 그대로 보여 준다. 이유는 그 거래의 주인 모듈이 적는다(같은 보류를 두 번 적지 않는다).
 *
 * @param code        모듈 접두어를 붙인 이유 코드 (예: APPEARANCE_IN_PROGRESS). FE가 코드별로 바로가기를 붙인다
 * @param message     사용자에게 그대로 보여 줄 문장. 무엇을 정리하면 되는지 적는다
 * @param availableAt 기다리면 풀리는 경우 그 날짜 (KST). 없으면 null
 */
public record WithdrawalBlockReasonDto(String code, String message, LocalDate availableAt) {

    // 빠진 응답은 Client가 읽을 수 없는 응답으로 거절한다
    public WithdrawalBlockReasonDto {
        Objects.requireNonNull(code, "code는 필수입니다.");
        Objects.requireNonNull(message, "message는 필수입니다.");
    }

    /** 기다려도 풀리지 않는 이유 (회원이 정리해야 한다) */
    public static WithdrawalBlockReasonDto of(String code, String message) {
        return new WithdrawalBlockReasonDto(code, message, null);
    }
}
