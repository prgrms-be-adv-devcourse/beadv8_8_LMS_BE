package com.hapbang.shared.appearance.dto;

import java.time.LocalDateTime;

/**
 * 다른 모듈에 넘기는 출연 정보 (호스트 일방 취소·출연 확인·불참 알림 등에 쓴다, #871).
 * 출연은 제목을 따로 갖지 않으므로 출처(요청서·모집글)의 제목을 담는다.
 *
 * @param appearanceId 출연 ID
 * @param title        출처 요청서·모집글 제목
 * @param hostId       호스트 회원 ID
 * @param guestId      게스트 회원 ID
 * @param scheduledAt  방송 예정 일시 (KST)
 */
public record AppearanceDto(
        Long appearanceId,
        String title,
        Long hostId,
        Long guestId,
        LocalDateTime scheduledAt
) {
}
