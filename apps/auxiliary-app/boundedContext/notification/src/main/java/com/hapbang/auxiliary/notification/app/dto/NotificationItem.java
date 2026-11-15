package com.hapbang.auxiliary.notification.app.dto;

import java.time.Instant;

import com.hapbang.auxiliary.notification.domain.Notification;

import io.swagger.v3.oas.annotations.media.Schema;

/** 알림 목록 항목. 값(payload)은 보내지 않는다: 문구는 서버가 만든다(ND-020) */
@Schema(description = "알림 한 건")
public record NotificationItem(
        @Schema(description = "알림 ID") Long id,
        @Schema(description = "종류 코드") String kind,
        @Schema(description = "문구") String message,
        @Schema(description = "눌렀을 때 이동할 대상 종류") String targetType,
        @Schema(description = "이동할 대상 ID") String targetId,
        @Schema(description = "발생 시각(정렬 기준)") Instant occurredAt,
        @Schema(description = "읽은 시각. 안 읽었으면 null") Instant readAt
) {

    public static NotificationItem from(Notification notification) {
        return new NotificationItem(notification.getId(), notification.getKind(), notification.getMessage(),
                notification.getTargetType(), notification.getTargetId(), notification.getOccurredAt(),
                notification.getReadAt());
    }
}
