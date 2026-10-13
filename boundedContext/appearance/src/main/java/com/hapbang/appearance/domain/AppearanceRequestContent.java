package com.hapbang.appearance.domain;

import java.time.LocalDateTime;

/**
 * 출연 요청서를 보낼 때 호스트가 입력하는 내용.
 *
 * @param scheduledAt     방송 예정 일시
 * @param expectedMinutes 예상 출연 시간(분)
 * @param content         콘텐츠 설명
 * @param fee             출연료 (HP)
 * @param acceptDeadline  수락 기한
 */
public record AppearanceRequestContent(String title,
                                       String hostChannelUrl,
                                       LocalDateTime scheduledAt,
                                       int expectedMinutes,
                                       String content,
                                       long fee,
                                       LocalDateTime acceptDeadline) {
}
