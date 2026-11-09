package com.hapbang.auxiliary.chatbot.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 적용기 판정 (#716). 이벤트와 싱크가 같은 판정을 쓴다 */
class CopyOutcomeTest {

    @Test
    @DisplayName("version 있는 데이터: 판정 6가지")
    void versioned() {
        assertThat(CopyOutcome.decide(null, null, 0L, "a")).isEqualTo(CopyOutcome.INSERTED);
        assertThat(CopyOutcome.decide(1L, "a", 2L, "b")).isEqualTo(CopyOutcome.SOURCE_NEWER);
        assertThat(CopyOutcome.decide(1L, "a", 2L, "a")).isEqualTo(CopyOutcome.VERSION_ONLY);
        assertThat(CopyOutcome.decide(1L, "a", 1L, "a")).isEqualTo(CopyOutcome.UNCHANGED);
        assertThat(CopyOutcome.decide(1L, "a", 1L, "b")).isEqualTo(CopyOutcome.SAME_VERSION_DIFFERENT);
        assertThat(CopyOutcome.decide(2L, "a", 1L, "b")).isEqualTo(CopyOutcome.OURS_NEWER);
    }

    @Test
    @DisplayName("version 없는 데이터(리뷰): 없으면 넣고, 해시가 같으면 없음, 다르면 고친다")
    void versionless() {
        assertThat(CopyOutcome.decide(null, null, null, "a")).isEqualTo(CopyOutcome.INSERTED);
        assertThat(CopyOutcome.decide(null, "a", null, "a")).isEqualTo(CopyOutcome.UNCHANGED);
        assertThat(CopyOutcome.decide(null, "a", null, "b")).isEqualTo(CopyOutcome.SAME_VERSION_DIFFERENT);
    }

    @Test
    @DisplayName("어긋남 기록·고친 건수: version만 오른 것과 같은 것은 정상이다")
    void mismatchAndFixed() {
        assertThat(CopyOutcome.INSERTED.mismatch()).isEqualTo(SyncMismatchKind.ONLY_IN_SOURCE);
        assertThat(CopyOutcome.SOURCE_NEWER.mismatch()).isEqualTo(SyncMismatchKind.SOURCE_NEWER);
        assertThat(CopyOutcome.SAME_VERSION_DIFFERENT.mismatch()).isEqualTo(SyncMismatchKind.SAME_VERSION_DIFFERENT);
        assertThat(CopyOutcome.OURS_NEWER.mismatch()).isEqualTo(SyncMismatchKind.OURS_NEWER);
        assertThat(CopyOutcome.VERSION_ONLY.mismatch()).isNull();
        assertThat(CopyOutcome.UNCHANGED.mismatch()).isNull();

        assertThat(CopyOutcome.values()).filteredOn(CopyOutcome::fixed)
                .containsExactlyInAnyOrder(CopyOutcome.SOURCE_NEWER, CopyOutcome.SAME_VERSION_DIFFERENT);
    }

    @Test
    @DisplayName("경고: (넣은 + 고친) / 읽은 건수가 비율 이상이고 최소 건수 이상일 때만. version만 오른 건은 세지 않는다")
    void warning() {
        SyncTally tally = new SyncTally();
        for (int i = 0; i < 7; i++) {
            tally.add(CopyOutcome.UNCHANGED);
        }
        tally.add(CopyOutcome.INSERTED);
        tally.add(CopyOutcome.SOURCE_NEWER);
        tally.add(CopyOutcome.SAME_VERSION_DIFFERENT);
        // 3 / 10 = 0.3
        assertThat(tally.warns(0.3, 3)).isTrue();
        assertThat(tally.warns(0.3, 4)).isFalse();
        assertThat(tally.warns(0.31, 1)).isFalse();

        SyncTally versionOnly = new SyncTally();
        for (int i = 0; i < 10; i++) {
            versionOnly.add(CopyOutcome.VERSION_ONLY);
        }
        assertThat(versionOnly.warns(0.3, 0)).isFalse();
        assertThat(versionOnly.fixed()).isZero();
        assertThat(new SyncTally().warns(0.3, 0)).isFalse();
    }
}
