package com.hapbang.appearance.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** HP 작업 사유. 출연 내부 기록이며 Billing에는 보내지 않는다 (Billing 반환·지급 요청에는 사유가 없다) */
@Getter
@RequiredArgsConstructor
public enum HpTaskReason {
    REQUEST_REJECTED("게스트가 출연 요청을 거절"),
    REQUEST_CANCELED("게스트 응답 전에 호스트가 출연 요청을 취소"),
    REQUEST_EXPIRED("수락 기한까지 게스트 응답 없음"),
    RECRUIT_CLOSED("모집 마감 때 성립하지 못한 인원분"),
    CANCEL_AGREED("게스트의 출연 취소 요청을 호스트가 수락 (합의 취소)"),
    HOST_CANCELED("호스트 일방 취소 (게스트에게 위약금 지급)"),
    RECRUIT_CANCELED("모집 전체 취소 때 비어 있는 자리");

    // 합의 취소·노쇼·지급 등은 해당 처리에서 추가한다.

    private final String description;
}
