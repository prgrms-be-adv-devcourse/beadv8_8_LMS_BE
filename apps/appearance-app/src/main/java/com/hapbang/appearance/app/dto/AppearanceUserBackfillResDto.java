package com.hapbang.appearance.app.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 관리자 기존 회원 Backfill 한 번의 실행 결과 (#394). 회원 정보(닉네임 등)는 담지 않고 집계와 회원 ID만 담는다.
 * scanned = created + updated + ignored + failed.
 */
public record AppearanceUserBackfillResDto(
        @Schema(description = "읽은 회원 수") long scanned,
        @Schema(description = "출연 회원이 없어 만든 수") long created,
        @Schema(description = "회원 버전이 더 높아 갱신한 수") long updated,
        @Schema(description = "이미 같거나 더 높은 버전이라 그대로 둔 수") long ignored,
        @Schema(description = "반영에 실패한 수 (다시 실행하면 다시 시도한다)") long failed,
        @Schema(description = "실패한 회원 ID (앞쪽 최대 100개)") List<Long> failedIds,
        @Schema(description = "마지막으로 처리한 회원 ID. 읽은 회원이 없으면 null") Long lastProcessedId,
        @Schema(description = "다음 실행의 afterId. 끝까지 읽었으면 null") Long nextAfterId,
        @Schema(description = "회원 목록을 끝까지 읽었는지. 모두 성공했는지는 failed로 본다") boolean completed
) {

    public AppearanceUserBackfillResDto {
        failedIds = List.copyOf(failedIds);
    }
}
