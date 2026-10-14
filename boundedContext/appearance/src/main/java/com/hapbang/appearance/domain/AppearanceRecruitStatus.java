package com.hapbang.appearance.domain;

import java.util.List;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 출연 모집글 상태.
 * <pre>
 * HOLD_PENDING ─보류 성공─▶ RECRUITING ─▶ CLOSED (기한 또는 조기 마감) ─▶ CANCELED (마감 후 모집 전체 취소)
 *              │                       └─▶ CANCELED (모집 중 모집 전체 취소)
 *              └─Billing 거절(잔액 부족 등)─▶ HOLD_FAILED
 * </pre>
 * 마감·취소는 모집 마감·모집 취소 처리에서 쓴다.
 */
@Getter
@RequiredArgsConstructor
public enum AppearanceRecruitStatus {
    HOLD_PENDING("HP 보류 처리 중. 저장 후 보류 결과를 기다리며, 결과를 알 수 없으면 이 상태로 남는다"),
    HOLD_FAILED("HP 보류 실패. Billing이 거절해(잔액 부족 등) 등록되지 않았고 기록으로만 남는다"),
    RECRUITING("모집 중. HP 보류가 끝나 게스트의 지원을 받는다"),
    CLOSED("모집 마감. 마감 일시가 지났거나 호스트가 조기 마감해 더 이상 지원·수락할 수 없다"),
    CANCELED("모집 전체 취소. 호스트가 방송 예정 일시 전에 모집을 취소했다");

    /** 목록·상세에 보이는 상태. 보류가 끝나지 않았거나 실패한 모집글은 숨긴다 */
    public static final List<AppearanceRecruitStatus> VISIBLE = List.of(RECRUITING, CLOSED, CANCELED);

    private final String description;

    public boolean isVisible() {
        return VISIBLE.contains(this);
    }
}
