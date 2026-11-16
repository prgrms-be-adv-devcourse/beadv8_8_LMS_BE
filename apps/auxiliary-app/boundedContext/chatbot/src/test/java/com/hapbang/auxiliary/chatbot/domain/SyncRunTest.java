package com.hapbang.auxiliary.chatbot.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 싱크 회차의 오류 자르기 (#835): 컬럼 길이(1000자)를 코드포인트로 세어 이모지를 반으로 자르지 않는다 */
class SyncRunTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 16, 9, 0);

    @Test
    @DisplayName("실패 오류가 1000자를 넘으면 코드포인트 1000개까지 남긴다. 경계의 이모지는 반으로 자르지 않는다")
    void failErrorCutsByCodePoint() {
        SyncRun run = SyncRun.start(SyncType.MEMBER, SyncTrigger.SCHEDULED, null, NOW);

        run.fail(new SyncTally(), "a".repeat(SyncRun.ERROR_MAX_LENGTH - 1) + "😀" + "b".repeat(10), NOW);

        assertThat(run.getError()).isEqualTo("a".repeat(SyncRun.ERROR_MAX_LENGTH - 1) + "😀");
    }

    @Test
    @DisplayName("건너뛴 회차의 사유도 같은 기준으로 자른다. 짧으면 그대로")
    void skippedReasonCutsByCodePoint() {
        String reason = "a".repeat(SyncRun.ERROR_MAX_LENGTH - 1) + "😀" + "b";

        assertThat(SyncRun.skipped(SyncType.MEMBER, SyncTrigger.SCHEDULED, reason, NOW).getError())
                .isEqualTo("a".repeat(SyncRun.ERROR_MAX_LENGTH - 1) + "😀");
        assertThat(SyncRun.skipped(SyncType.MEMBER, SyncTrigger.SCHEDULED, "락을 잡지 못함", NOW).getError())
                .isEqualTo("락을 잡지 못함");
    }
}
