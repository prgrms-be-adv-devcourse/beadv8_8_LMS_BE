package com.hapbang.auxiliary.chatbot.app;

import static com.hapbang.auxiliary.chatbot.support.CopyRows.appearance;
import static com.hapbang.auxiliary.chatbot.support.CopyRows.review;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.auxiliary.chatbot.domain.CopyOutcome;
import com.hapbang.auxiliary.chatbot.support.ChatbotIntegrationTestBase;
import com.hapbang.auxiliary.chatbot.support.CopyRows;
import com.hapbang.shared.appearance.domain.AppearanceResult;
import com.hapbang.shared.appearance.dto.AppearanceParticipantRole;
import com.hapbang.shared.appearance.dto.AppearanceSyncDto;
import com.hapbang.shared.appearance.event.AppearanceReviewPublishedEvent;

/** 적용기를 실제 DB(upsert 한 문장)로 검증한다 (#716) */
class CopyApplierTest extends ChatbotIntegrationTestBase {

    @Autowired
    private CopyApplier copyApplier;

    @Test
    @DisplayName("판정 6가지가 사본에 그대로 반영된다: 넣기, 원본 새것, version만, 같음, 같은 version 다른 값, 우리 새것")
    void outcomes() {
        assertThat(apply(appearance(1, AppearanceResult.SCHEDULED, 0))).isEqualTo(CopyOutcome.INSERTED);
        assertThat(apply(appearance(1, AppearanceResult.COMPLETED, 2))).isEqualTo(CopyOutcome.SOURCE_NEWER);
        assertThat(rows.appearance(1)).containsEntry("result", "COMPLETED").containsEntry("version", 2L);

        assertThat(apply(appearance(1, AppearanceResult.COMPLETED, 3))).isEqualTo(CopyOutcome.VERSION_ONLY);
        assertThat(rows.appearance(1)).containsEntry("version", 3L);

        assertThat(apply(appearance(1, AppearanceResult.COMPLETED, 3))).isEqualTo(CopyOutcome.UNCHANGED);
        assertThat(apply(appearance(1, AppearanceResult.NO_SHOW, 3))).isEqualTo(CopyOutcome.SAME_VERSION_DIFFERENT);
        assertThat(rows.appearance(1)).containsEntry("result", "NO_SHOW");

        assertThat(apply(appearance(1, AppearanceResult.CANCELED, 1))).isEqualTo(CopyOutcome.OURS_NEWER);
        assertThat(rows.appearance(1)).containsEntry("result", "NO_SHOW").containsEntry("version", 3L);
    }

    @Test
    @DisplayName("같은 이벤트를 두 번 받아도 결과가 같다(멱등). 바꿀 값이 없으면 행을 건드리지 않는다")
    void idempotent() {
        AppearanceSyncDto event = appearance(1, AppearanceResult.SCHEDULED, 0);
        apply(event);
        Map<String, Object> first = rows.appearance(1);
        clock.advance(Duration.ofMinutes(1));

        assertThat(apply(event)).isEqualTo(CopyOutcome.UNCHANGED);
        assertThat(rows.appearance(1)).isEqualTo(first);
        assertThat(first.get("seen_run_id")).isNull();
    }

    @Test
    @DisplayName("늦게 온 옛 이벤트(우리가 새것·같음)는 받은 시각도 원본 없음 표시도 바꾸지 않는다. 싱크는 같아도 본 표시를 남긴다")
    void staleEventIgnored() {
        apply(appearance(1, AppearanceResult.COMPLETED, 3));
        jdbcTemplate.update("UPDATE chatbot.appearance_copy SET missing_in_source = TRUE WHERE id = 1");
        Map<String, Object> before = rows.appearance(1);
        clock.advance(Duration.ofMinutes(1));

        assertThat(apply(appearance(1, AppearanceResult.SCHEDULED, 2))).isEqualTo(CopyOutcome.OURS_NEWER);
        assertThat(apply(appearance(1, AppearanceResult.COMPLETED, 3))).isEqualTo(CopyOutcome.UNCHANGED);
        assertThat(rows.appearance(1)).isEqualTo(before);

        // 싱크 회차가 원본에서 같은 값을 보면 원본에 있다는 표시를 남긴다(회차 끝 원본 없음 표시가 잡지 않게)
        assertThat(copyApplier.apply(appearance(1, AppearanceResult.COMPLETED, 3), 77L).outcome())
                .isEqualTo(CopyOutcome.UNCHANGED);
        assertThat(rows.appearance(1)).containsEntry("seen_run_id", 77L).containsEntry("missing_in_source", false)
                .containsEntry("version", 3L);
        assertThat(rows.appearance(1).get("synced_at")).isNotEqualTo(before.get("synced_at"));

        // 리뷰도 같다: 같은 값의 이벤트는 건드리지 않고, 싱크는 본 표시를 남긴다
        copyApplier.apply(review(10, 1, 4), null);
        jdbcTemplate.update("UPDATE chatbot.appearance_review_copy SET missing_in_source = TRUE WHERE review_id = 10");
        Map<String, Object> reviewBefore = rows.review(10);
        assertThat(copyApplier.apply(review(10, 1, 4), null).outcome()).isEqualTo(CopyOutcome.UNCHANGED);
        assertThat(rows.review(10)).isEqualTo(reviewBefore);
        copyApplier.apply(review(10, 1, 4), 77L);
        assertThat(rows.review(10)).containsEntry("seen_run_id", 77L).containsEntry("missing_in_source", false);
    }

    @Test
    @DisplayName("리뷰는 출연보다 먼저 와도 넣는다. 공개 이벤트와 싱크 값이 같으면 다시 받아도 그대로다")
    void reviewBeforeAppearance() {
        AppearanceReviewPublishedEvent event = new AppearanceReviewPublishedEvent(10L, 99L, 3L, 2L,
                AppearanceParticipantRole.GUEST, 4, CopyRows.SCHEDULED_AT);

        assertThat(copyApplier.apply(event).outcome()).isEqualTo(CopyOutcome.INSERTED);
        assertThat(rows.appearanceCount()).isZero();
        assertThat(rows.review(10)).containsEntry("appearance_id", 99L).containsEntry("reviewee_user_id", 2L)
                .containsEntry("rating", 4);

        assertThat(copyApplier.apply(review(10, 99, 4), null).outcome()).isEqualTo(CopyOutcome.UNCHANGED);
        assertThat(copyApplier.apply(review(10, 99, 5), null).outcome()).isEqualTo(CopyOutcome.SAME_VERSION_DIFFERENT);
        assertThat(rows.review(10)).containsEntry("rating", 5);
    }

    @Test
    @DisplayName("이벤트와 싱크가 같은 행을 동시에 반영해도 큰 version이 남는다")
    void concurrentKeepsNewest() throws Exception {
        for (int round = 0; round < 20; round++) {
            long id = 100 + round;
            AppearanceSyncDto older = appearance(id, AppearanceResult.SCHEDULED, 1);
            AppearanceSyncDto newer = appearance(id, AppearanceResult.COMPLETED, 2);
            CountDownLatch start = new CountDownLatch(1);
            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                List<Future<?>> futures = new ArrayList<>();
                for (AppearanceSyncDto dto : List.of(newer, older)) {
                    futures.add(executor.submit(() -> {
                        start.await();
                        return copyApplier.apply(dto, null);
                    }));
                }
                start.countDown();
                for (Future<?> future : futures) {
                    future.get(10, TimeUnit.SECONDS);
                }
            }
            assertThat(rows.appearance(id)).containsEntry("version", 2L).containsEntry("result", "COMPLETED");
        }
    }

    @Test
    @DisplayName("원본 없음으로 표시한 행이 새 version 이벤트로 다시 들어오면 표시를 푼다")
    void reappearanceClearsMissing() {
        apply(appearance(1, AppearanceResult.SCHEDULED, 0));
        jdbcTemplate.update("UPDATE chatbot.appearance_copy SET missing_in_source = TRUE WHERE id = 1");

        apply(appearance(1, AppearanceResult.SCHEDULED, 1));

        assertThat(rows.missingAppearances()).isEmpty();
    }

    private CopyOutcome apply(AppearanceSyncDto dto) {
        return copyApplier.apply(dto, null).outcome();
    }
}
