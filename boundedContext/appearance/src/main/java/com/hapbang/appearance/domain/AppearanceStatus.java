package com.hapbang.appearance.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 출연 상태.
 * <pre>
 * ESTABLISHED ⇄ CANCEL_REQUESTED (거절되면 ESTABLISHED로 복귀)
 *     → CONFIRMED / NO_SHOW / CANCELED_BY_HOST / CANCELED_BY_AGREEMENT
 * </pre>
 * 성립·출연 취소 요청·합의 취소(#231)를 쓰고, 확인·노쇼·호스트 일방 취소는 이후 처리에서 쓴다.
 */
@Getter
@RequiredArgsConstructor
public enum AppearanceStatus {
    ESTABLISHED("성립. 게스트 수락(요청) 또는 호스트 수락(모집)으로 거래가 맺어졌다. HP는 보류된 상태"),
    CANCEL_REQUESTED("출연 취소 요청 중. 게스트가 취소를 요청해 호스트 응답을 기다린다"),
    CONFIRMED("출연 확인. 호스트 긍정 응답 또는 방송 예정 일시 D+7 자동 확인으로 게스트에게 지급"),
    NO_SHOW("노쇼. 호스트 부정 응답으로 보류 HP를 호스트에게 반환"),
    CANCELED_BY_HOST("호스트 일방 취소. 위약금을 게스트에게 지급하고 나머지는 호스트에게 반환"),
    CANCELED_BY_AGREEMENT("합의 취소. 호스트가 게스트의 출연 취소 요청을 수락 (위약금 없음)");

    private final String description;
}
