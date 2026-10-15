package com.hapbang.shared.appearance.event;

import com.hapbang.shared.appearance.dto.AppearanceRequestDto;

/** 출연 요청서가 게스트에게 도착했다 (HP 보류가 끝나 응답을 기다리는 상태가 됨). 요청서 하나에 한 번 발행된다 */
public record AppearanceRequestSentEvent(AppearanceRequestDto request) {
}
