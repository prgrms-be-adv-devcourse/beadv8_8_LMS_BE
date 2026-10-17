package com.hapbang.appearance.domain;

import java.time.LocalDateTime;

/**
 * 출연 모집글을 올릴 때 호스트가 입력하는 내용.
 *
 * @param scheduledAt     방송 예정 일시
 * @param expectedMinutes 예상 출연 시간(분)
 * @param content         콘텐츠 설명
 * @param feePerPerson    1인당 출연료 (HP)
 * @param recruitCount    모집 인원
 * @param recruitDeadline 모집 마감 일시
 */
public record AppearanceRecruitContent(
        String title,
        String hostChannelUrl,
        LocalDateTime scheduledAt,
        int expectedMinutes,
        String content,
        long feePerPerson,
        int recruitCount,
        LocalDateTime recruitDeadline
) {
}
