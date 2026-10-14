package com.hapbang.appearance.domain;

import java.util.List;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 출연 요청서 상태.
 * <pre>
 * HOLD_PENDING ─보류 성공─▶ REQUESTED ─▶ ACCEPTED / REJECTED / EXPIRED / CANCELED
 *              └─Billing 거절(잔액 부족 등)─▶ HOLD_FAILED
 * </pre>
 * 수락·거절·만료·취소는 게스트 응답 처리에서 쓴다.
 */
@Getter
@RequiredArgsConstructor
public enum AppearanceRequestStatus {
    HOLD_PENDING("HP 보류 처리 중. 저장 후 보류 결과를 기다리며, Billing 무응답이면 이 상태로 남는다"),
    HOLD_FAILED("HP 보류 실패. Billing이 거절해(잔액 부족 등) 등록되지 않았고 기록으로만 남는다"),
    REQUESTED("응답 대기. HP 보류가 끝나 게스트의 수락·거절을 기다린다"),
    ACCEPTED("수락. 게스트가 수락해 출연이 성립했다"),
    REJECTED("거절. 게스트가 거절해 보류 HP를 호스트에게 반환했다"),
    EXPIRED("만료. 수락 기한까지 응답이 없어 보류 HP를 호스트에게 반환했다"),
    CANCELED("취소. 게스트 응답 전에 호스트가 취소해 보류 HP를 호스트에게 반환했다");

    /** 같은 호스트·게스트 사이에 1건만 둘 수 있는 진행 중 상태 (DB 부분 유니크 인덱스와 같은 기준) */
    public static final List<AppearanceRequestStatus> IN_PROGRESS = List.of(HOLD_PENDING, REQUESTED);

    /** 목록·상세에 보이는 상태. 보류가 끝나지 않았거나 실패한 요청서는 숨긴다 */
    public static final List<AppearanceRequestStatus> VISIBLE =
            List.of(REQUESTED, ACCEPTED, REJECTED, EXPIRED, CANCELED);

    private final String description;

    public boolean isVisible() {
        return VISIBLE.contains(this);
    }
}
