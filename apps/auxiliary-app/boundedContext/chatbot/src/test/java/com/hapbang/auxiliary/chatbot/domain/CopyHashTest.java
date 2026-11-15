package com.hapbang.auxiliary.chatbot.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.shared.appearance.domain.AppearanceResult;
import com.hapbang.shared.appearance.dto.AppearanceReviewSyncDto;
import com.hapbang.shared.appearance.dto.AppearanceSyncDto;
import com.hapbang.shared.appearance.dto.AppearanceParticipantRole;
import com.hapbang.shared.appearance.event.AppearanceReviewPublishedEvent;

/** 사본 값의 해시와 시각 맞춤 (#716) */
class CopyHashTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 20, 20, 0, 0, 123_456_789);

    @Test
    @DisplayName("시각: offset 없는 KST와 +09:00 offset 값은 같은 KST LocalDateTime(마이크로초)이 된다. 다른 offset은 KST로 옮긴다")
    void times() {
        LocalDateTime expected = LocalDateTime.of(2026, 11, 20, 20, 0, 0, 123_456_000);
        assertThat(CopyTimes.kst(AT)).isEqualTo(expected);
        assertThat(CopyTimes.kst(OffsetDateTime.of(AT, ZoneOffset.ofHours(9)))).isEqualTo(expected);
        assertThat(CopyTimes.kst(OffsetDateTime.of(AT.minusHours(9), ZoneOffset.UTC))).isEqualTo(expected);
        assertThat(CopyTimes.kst((LocalDateTime) null)).isNull();
    }

    @Test
    @DisplayName("같은 시각이면 offset 유무·나노초 차이와 상관없이 해시가 같다")
    void sameInstantSameHash() {
        record Value(Long id, LocalDateTime at) {
        }
        String fromLocal = CopyHash.of(new Value(1L, CopyTimes.kst(AT)));
        String fromOffset = CopyHash.of(new Value(1L, CopyTimes.kst(OffsetDateTime.of(AT.plusNanos(1), ZoneOffset.ofHours(9)))));
        assertThat(fromLocal).isEqualTo(fromOffset).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("필드 이름 순으로 정렬하고 null도 쓴다")
    void sortedJson() {
        record Value(String b, Long a, String c) {
        }
        assertThat(CopyHash.json(new Value("x", 1L, null))).isEqualTo("{\"a\":1,\"b\":\"x\",\"c\":null}");
        assertThat(CopyHash.of(new Value("x", 1L, null))).isNotEqualTo(CopyHash.of(new Value("x", 1L, "")));
    }

    @Test
    @DisplayName("출연 해시는 version을 넣지 않는다. 값이 하나라도 다르면 해시가 다르다")
    void appearanceHashIgnoresVersion() {
        AppearanceCopy v0 = AppearanceCopy.from(new AppearanceSyncDto(1L, 2L, 3L, AppearanceResult.SCHEDULED, AT, 0L));
        AppearanceCopy v1 = AppearanceCopy.from(new AppearanceSyncDto(1L, 2L, 3L, AppearanceResult.SCHEDULED, AT, 1L));
        AppearanceCopy done = AppearanceCopy.from(new AppearanceSyncDto(1L, 2L, 3L, AppearanceResult.COMPLETED, AT, 2L));

        assertThat(v0.hash()).isEqualTo(v1.hash()).isNotEqualTo(done.hash());
        assertThat(v1.version()).isEqualTo(1L);
        assertThat(v0.scheduledAt()).isEqualTo(CopyTimes.kst(AT));
    }

    @Test
    @DisplayName("리뷰: 공개 이벤트에서 싱크 DTO 4필드만 뽑아 싱크로 받은 값과 해시가 같다. 필수 값이 없으면 예외")
    void reviewEventMatchesSyncDto() {
        AppearanceReviewPublishedEvent event = new AppearanceReviewPublishedEvent(10L, 1L, 3L, 2L,
                AppearanceParticipantRole.GUEST, 5, AT);

        AppearanceReviewCopy fromEvent = AppearanceReviewCopy.from(event);
        AppearanceReviewCopy fromSync = AppearanceReviewCopy.from(new AppearanceReviewSyncDto(10L, 1L, 2L, 5));

        assertThat(fromEvent).isEqualTo(fromSync);
        assertThatThrownBy(() -> AppearanceReviewCopy.from(new AppearanceReviewPublishedEvent(null, 1L, 3L, 2L,
                AppearanceParticipantRole.GUEST, 5, AT))).isInstanceOf(NullPointerException.class);
    }
}
