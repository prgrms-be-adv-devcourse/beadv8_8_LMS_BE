package com.hapbang.appearance.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 출연 취소 요청 상태.
 * <pre>
 * REQUESTED ─호스트 수락─▶ ACCEPTED (합의 취소)
 *           ├호스트 거절─▶ REJECTED (출연 유지, 1회 재요청 가능)
 *           ├방송 예정 일시까지 응답 없음─▶ AUTO_REJECTED (출연 유지)
 *           └호스트가 확인 후 일방 취소─▶ CLOSED_BY_HOST_CANCEL (위약금 지급, #239)
 * </pre>
 */
@Getter
@RequiredArgsConstructor
public enum AppearanceCancelRequestStatus {
    REQUESTED("처리 중. 호스트의 수락·거절을 기다린다"),
    ACCEPTED("수락. 합의 취소되어 출연이 끝났다 (위약금 없음)"),
    REJECTED("거절. 호스트가 거절해 출연이 유지된다"),
    AUTO_REJECTED("자동 거절. 방송 예정 일시까지 호스트가 응답하지 않아 출연이 유지된다"),
    CLOSED_BY_HOST_CANCEL("호스트 일방 취소로 종료. 호스트가 처리 중인 요청을 확인하고 일방 취소해 게스트에게 위약금이 지급된다");

    private final String description;
}
