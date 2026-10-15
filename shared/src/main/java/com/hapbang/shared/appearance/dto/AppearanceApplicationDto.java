package com.hapbang.shared.appearance.dto;

import java.time.LocalDateTime;

/**
 * 다른 모듈에 넘기는 출연 모집 지원 정보 (지원 도착 알림 등에 쓴다). 지원 메시지는 담지 않는다.
 *
 * @param applicationId 지원 ID
 * @param recruitId     지원한 모집글 ID
 * @param recruitTitle  모집글 제목
 * @param hostId        모집글을 올린 호스트 회원 ID
 * @param guestId       지원한 게스트 회원 ID
 * @param appliedAt     지원 시각 (KST)
 */
public record AppearanceApplicationDto(
        Long applicationId,
        Long recruitId,
        String recruitTitle,
        Long hostId,
        Long guestId,
        LocalDateTime appliedAt
) {
}
