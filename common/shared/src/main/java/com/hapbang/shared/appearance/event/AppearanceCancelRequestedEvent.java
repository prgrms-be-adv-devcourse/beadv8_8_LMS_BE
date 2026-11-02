package com.hapbang.shared.appearance.event;

import com.hapbang.shared.appearance.dto.AppearanceCancelRequestDto;

/** 게스트가 출연 취소를 요청했다 (호스트 응답을 기다림). 요청 하나에 한 번 발행된다 */
public record AppearanceCancelRequestedEvent(AppearanceCancelRequestDto cancelRequest) {
}
