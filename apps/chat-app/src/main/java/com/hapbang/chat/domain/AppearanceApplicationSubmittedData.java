package com.hapbang.chat.domain;

import com.hapbang.shared.appearance.dto.AppearanceApplicationDto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 출연 모집 지원 도착({@link NotificationKind#APPEARANCE_APPLICATION_SUBMITTED}) 알림의 값. 형식은 {@link NotificationData}를 따른다.
 *
 * @param v             형식 버전
 * @param guestNickname 지원한 게스트의 닉네임. 알림을 남길 때의 채팅 회원 복제본 값이다
 * @param recruitTitle  모집글 제목
 */
@Schema(description = "출연 모집 지원 도착(APPEARANCE_APPLICATION_SUBMITTED) 알림의 값")
public record AppearanceApplicationSubmittedData(
        @Schema(description = "형식 버전", example = "1") int v,
        @Schema(description = "지원한 게스트 닉네임(알림 당시 값)", example = "게스트") String guestNickname,
        @Schema(description = "모집글 제목", example = "주말 게임 합방 모집") String recruitTitle
) {

    /**
     * @param guestNickname 지원한 게스트의 닉네임 (채팅 회원 복제본)
     */
    public static AppearanceApplicationSubmittedData of(String guestNickname, AppearanceApplicationDto application) {
        return new AppearanceApplicationSubmittedData(NotificationData.VERSION, guestNickname,
                application.recruitTitle());
    }
}
