package com.hapbang.auxiliary.notification.app.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 안 읽은 알림 수. 보이는 최신 20개 안에서만 센다(ND-022) */
public record UnreadCount(@Schema(description = "안 읽은 알림 수(최대 20)") long count) {
}
