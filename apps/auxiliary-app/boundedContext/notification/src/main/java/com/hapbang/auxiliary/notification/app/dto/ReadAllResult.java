package com.hapbang.auxiliary.notification.app.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 전체 읽음 결과 */
public record ReadAllResult(@Schema(description = "이번에 읽음으로 바뀐 알림 수") int updatedCount) {
}
