package com.hapbang.auxiliary.chatbot.app.dto;

import java.util.List;

import com.hapbang.auxiliary.chatbot.domain.SyncMismatch;
import com.hapbang.auxiliary.chatbot.domain.SyncMismatchKind;
import com.hapbang.auxiliary.chatbot.domain.SyncRun;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 싱크 회차 상세 (#716). 어긋남은 회차당 최대 N건, 종류마다 최근 M회만 남아 있다. 첫 실행은 어긋남 행이 없다(건수만).
 */
public record SyncRunDetailResDto(
        SyncRunResDto run,
        List<Mismatch> mismatches
) {

    public static SyncRunDetailResDto from(SyncRun run, List<SyncMismatch> mismatches) {
        return new SyncRunDetailResDto(SyncRunResDto.from(run), mismatches.stream().map(Mismatch::from).toList());
    }

    /** 어긋난 항목 하나 */
    public record Mismatch(
            @Schema(description = "원본 ID(출연 ID, 리뷰 ID)") Long sourceId,
            @Schema(description = "ONLY_IN_SOURCE, SOURCE_NEWER, SAME_VERSION_DIFFERENT, OURS_NEWER, MISSING_IN_SOURCE")
            SyncMismatchKind kind,
            @Schema(description = "반영 전 우리 version. 없었거나 version 없는 데이터면 null") Long ourVersion,
            @Schema(description = "원본 version. version 없는 데이터·원본 없음이면 null") Long sourceVersion
    ) {

        static Mismatch from(SyncMismatch mismatch) {
            return new Mismatch(mismatch.getSourceId(), mismatch.getKind(), mismatch.getOurVersion(),
                    mismatch.getSourceVersion());
        }
    }
}
