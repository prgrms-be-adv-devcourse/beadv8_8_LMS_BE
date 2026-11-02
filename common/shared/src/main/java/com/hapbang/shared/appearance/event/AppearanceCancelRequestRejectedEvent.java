package com.hapbang.shared.appearance.event;

import com.hapbang.shared.appearance.dto.AppearanceCancelRequestDto;

/**
 * 출연 취소 요청이 거절돼 출연이 유지된다.
 *
 * @param autoRejected true면 방송 예정 일시까지 호스트가 응답하지 않아 자동으로 거절됐다
 */
public record AppearanceCancelRequestRejectedEvent(AppearanceCancelRequestDto cancelRequest, boolean autoRejected) {
}
