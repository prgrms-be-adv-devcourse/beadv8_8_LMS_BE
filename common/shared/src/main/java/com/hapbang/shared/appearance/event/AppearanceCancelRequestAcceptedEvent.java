package com.hapbang.shared.appearance.event;

import com.hapbang.shared.appearance.dto.AppearanceCancelRequestDto;

/** 호스트가 출연 취소 요청을 수락해 출연이 합의 취소됐다 (위약금 없음) */
public record AppearanceCancelRequestAcceptedEvent(AppearanceCancelRequestDto cancelRequest) {
}
