package com.hapbang.appearance.app.dto;

import com.hapbang.appearance.domain.AppearanceRecruit;
import com.hapbang.appearance.domain.AppearanceRecruitStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/** 모집글 요약 (내부 API, 채팅 모집용 단체방 #586) */
public record AppearanceRecruitInternalResDto(
        @Schema(description = "모집글 ID") Long recruitId,
        @Schema(description = "모집글을 올린 호스트 회원 ID") Long hostId,
        @Schema(description = "모집글 제목 (현재 값)") String title,
        @Schema(description = "모집글 상태. HP 보류 처리 중(HOLD_PENDING)·실패(HOLD_FAILED)도 그대로 준다") AppearanceRecruitStatus status
) {

    public static AppearanceRecruitInternalResDto from(AppearanceRecruit recruit) {
        return new AppearanceRecruitInternalResDto(recruit.getId(), recruit.getHostId(), recruit.getTitle(),
                recruit.getStatus());
    }
}
