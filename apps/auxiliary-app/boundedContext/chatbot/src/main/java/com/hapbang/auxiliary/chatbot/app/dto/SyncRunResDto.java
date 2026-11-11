package com.hapbang.auxiliary.chatbot.app.dto;

import java.time.LocalDateTime;

import com.hapbang.auxiliary.chatbot.domain.SyncRun;
import com.hapbang.auxiliary.chatbot.domain.SyncRunStatus;
import com.hapbang.auxiliary.chatbot.domain.SyncTrigger;
import com.hapbang.auxiliary.chatbot.domain.SyncType;

import io.swagger.v3.oas.annotations.media.Schema;

/** 싱크 회차 (#716, 관리자 조회) */
public record SyncRunResDto(
        Long id,
        SyncType type,
        @Schema(description = "SCHEDULED(정기 배치, 종류마다 매시), RETRY(#717 전 재시도 배치, 지난 기록만), ADMIN(관리자 실행)") SyncTrigger trigger,
        @Schema(description = "관리자 실행이면 관리자 회원 ID") Long requestedBy,
        @Schema(description = "RUNNING, SUCCEEDED, FAILED, SKIPPED") SyncRunStatus status,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        @Schema(description = "원본에서 읽은 건수") long scanned,
        @Schema(description = "원본에만 있어 넣은 건수") long inserted,
        @Schema(description = "원본 새것·같은 version 다른 값으로 고친 건수(version만 오른 정상 건 제외)") long fixed,
        @Schema(description = "우리 version이 커서 무시한 건수") long oursNewer,
        @Schema(description = "원본 없음으로 새로 표시한 건수(성공 회차만)") long markedMissing,
        @Schema(description = "마스킹한 회원의 데이터라 버린 건수(경고 비율에서도 뺀다)") long discarded,
        @Schema(description = "(넣은 건수 + 고친 건수) / (읽은 건수 - 버린 건수)가 기준 이상") boolean warning,
        @Schema(description = "실패·건너뜀 이유") String error
) {

    public static SyncRunResDto from(SyncRun run) {
        return new SyncRunResDto(run.getId(), run.getType(), run.getTrigger(), run.getRequestedBy(), run.getStatus(),
                run.getStartedAt(), run.getFinishedAt(), run.getScanned(), run.getInserted(), run.getFixed(),
                run.getOursNewer(), run.getMarkedMissing(), run.getDiscarded(), run.isWarning(), run.getError());
    }
}
