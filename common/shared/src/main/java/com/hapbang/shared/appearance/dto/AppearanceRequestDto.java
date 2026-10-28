package com.hapbang.shared.appearance.dto;

import java.time.LocalDateTime;

/**
 * 다른 모듈에 넘기는 출연 요청서 정보 (요청 도착 알림 등에 쓴다).
 *
 * @param requestId      출연 요청서 ID
 * @param hostId         요청을 보낸 호스트 회원 ID
 * @param guestId        요청을 받은 게스트 회원 ID
 * @param title          요청서 제목
 * @param fee            출연료 (HP)
 * @param scheduledAt    방송 예정 일시 (KST)
 * @param acceptDeadline 게스트 수락 기한 (KST)
 */
public record AppearanceRequestDto(
        Long requestId,
        Long hostId,
        Long guestId,
        String title,
        long fee,
        LocalDateTime scheduledAt,
        LocalDateTime acceptDeadline
) {
}
