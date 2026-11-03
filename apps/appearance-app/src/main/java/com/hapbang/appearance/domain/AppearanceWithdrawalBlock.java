package com.hapbang.appearance.domain;

import java.util.Locale;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 회원 탈퇴가 막힌 출연 쪽 이유 (#624, docs/api/internal-withdrawals.md 3장). 이름이 곧 응답의 {@code code}다.
 * 기준: 탈퇴한 뒤에도 출연 때문에 그 회원 지갑으로 HP가 드나들 일이 남았거나(닫힌 지갑도 반환·지급은 받아 HP가 쓸 수 없게 남는다),
 * 상대가 그 회원의 응답을 기다리는 건이 있으면 막는다 (ISSUE.md APR-49 WB1~WB3).
 * 응답 순서는 이 선언 순서다. 문구는 회원에게 그대로 보이며, 건수가 있는 이유는 {@code %s}에 천 단위 쉼표를 넣은 건수가 들어간다.
 */
@Getter
@RequiredArgsConstructor
public enum AppearanceWithdrawalBlock {
    APPEARANCE_REQUEST_SENT("응답을 기다리는 출연 요청 %s건이 있어요. 요청을 취소한 뒤 탈퇴할 수 있어요."),
    APPEARANCE_REQUEST_RECEIVED("받은 출연 요청 %s건에 아직 응답하지 않았어요. 요청한 회원이 기다리고 있으니 거절한 뒤 탈퇴할 수 있어요."),
    APPEARANCE_RECRUITING("모집 중인 출연 모집 %s건이 있어요. 모집을 마감하거나 취소한 뒤 탈퇴할 수 있어요."),
    APPEARANCE_APPLICATION_PENDING("결과를 기다리는 출연 지원 %s건이 있어요. 호스트가 선정할 수 있으니 지원을 취소한 뒤 탈퇴할 수 있어요."),
    APPEARANCE_IN_PROGRESS("성립된 출연 %s건이 진행 중이라 탈퇴할 수 없어요. 출연이 끝나거나 취소된 뒤 탈퇴할 수 있어요."),
    APPEARANCE_HP_PROCESSING("출연 HP를 처리하고 있어요. 잠시 뒤 다시 시도해 주세요."),
    APPEARANCE_HP_CHECK_REQUIRED("출연 HP 처리에 문제가 있어 확인하고 있어요. 확인이 끝난 뒤 탈퇴할 수 있어요.");

    private final String messageFormat;

    /** 회원에게 보여 줄 문장. 건수가 없는 문장은 건수를 쓰지 않는다. 쉼표는 서버 언어 설정과 관계없이 같다 */
    public String message(long count) {
        return messageFormat.formatted(String.format(Locale.ROOT, "%,d", count));
    }
}
