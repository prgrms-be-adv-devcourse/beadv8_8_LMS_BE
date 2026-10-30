package com.hapbang.chat.domain;

import com.hapbang.shared.appearance.dto.AppearanceRequestDto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 출연 요청 도착({@link NotificationKind#APPEARANCE_REQUEST_SENT}) 알림의 값. 형식은 {@link NotificationData}를 따른다.
 *
 * @param v              형식 버전
 * @param hostNickname   요청을 보낸 호스트의 닉네임. 알림을 남길 때의 채팅 회원 복제본 값이다
 * @param title          출연 요청서 제목
 * @param fee            출연료 (HP)
 * @param scheduledAt    방송 예정 일시 (KST, {@code yyyy-MM-dd'T'HH:mm:ss})
 * @param acceptDeadline 게스트 수락 기한 (KST, {@code yyyy-MM-dd'T'HH:mm:ss})
 */
@Schema(description = "출연 요청 도착(APPEARANCE_REQUEST_SENT) 알림의 값")
public record AppearanceRequestSentData(
        @Schema(description = "형식 버전", example = "1") int v,
        @Schema(description = "요청을 보낸 호스트 닉네임(알림 당시 값)", example = "호스트") String hostNickname,
        @Schema(description = "출연 요청서 제목", example = "금요일 밤 합방") String title,
        @Schema(description = "출연료 (HP)", example = "50000") long fee,
        @Schema(description = "방송 예정 일시 (KST, 오프셋 없음)", example = "2026-11-20T21:00:00") String scheduledAt,
        @Schema(description = "게스트 수락 기한 (KST, 오프셋 없음)", example = "2026-11-13T23:59:00") String acceptDeadline
) {

    /**
     * @param hostNickname 요청을 보낸 호스트의 닉네임 (채팅 회원 복제본)
     */
    public static AppearanceRequestSentData of(String hostNickname, AppearanceRequestDto request) {
        return new AppearanceRequestSentData(NotificationData.VERSION, hostNickname, request.title(), request.fee(),
                NotificationData.kst(request.scheduledAt()), NotificationData.kst(request.acceptDeadline()));
    }
}
