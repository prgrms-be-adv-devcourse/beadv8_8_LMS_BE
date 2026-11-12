package com.hapbang.shared.appearance.dto;

import java.time.LocalDateTime;

/**
 * 다른 모듈에 넘기는 출연 모집글 정보 (모집 시작·마감·취소 알림 등에 쓴다, #871). 모집 내용·채널 주소는 담지 않는다.
 *
 * @param recruitId       모집글 ID
 * @param hostId          모집글을 올린 호스트 회원 ID
 * @param title           모집글 제목
 * @param recruitDeadline 모집 마감 일시 (KST)
 */
public record AppearanceRecruitDto(
        Long recruitId,
        Long hostId,
        String title,
        LocalDateTime recruitDeadline
) {
}
