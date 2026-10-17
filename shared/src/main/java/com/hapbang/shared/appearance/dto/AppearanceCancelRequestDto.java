package com.hapbang.shared.appearance.dto;

import java.time.LocalDateTime;

/**
 * 다른 모듈에 넘기는 출연 취소 요청 정보 (취소 요청 도착·결과 알림 등에 쓴다). 취소·거절 사유는 담지 않는다.
 *
 * @param cancelRequestId 출연 취소 요청 ID
 * @param appearanceId    출연 ID
 * @param title           출처 요청서·모집글 제목
 * @param hostId          호스트 회원 ID
 * @param guestId         취소를 요청한 게스트 회원 ID
 * @param sequence        이 출연에서 몇 번째 취소 요청인지 (1 또는 2)
 * @param scheduledAt     방송 예정 일시 (KST). 호스트는 이 시각 전까지 응답해야 한다
 */
public record AppearanceCancelRequestDto(
        Long cancelRequestId,
        Long appearanceId,
        String title,
        Long hostId,
        Long guestId,
        int sequence,
        LocalDateTime scheduledAt
) {
}
