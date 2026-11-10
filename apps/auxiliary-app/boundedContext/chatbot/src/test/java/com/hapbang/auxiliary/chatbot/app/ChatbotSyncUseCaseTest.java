package com.hapbang.auxiliary.chatbot.app;

import static com.hapbang.auxiliary.chatbot.support.CopyRows.appearance;
import static com.hapbang.auxiliary.chatbot.support.CopyRows.review;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.auxiliary.chatbot.domain.CopyTimes;
import com.hapbang.auxiliary.chatbot.domain.SyncMismatch;
import com.hapbang.auxiliary.chatbot.domain.SyncMismatchKind;
import com.hapbang.auxiliary.chatbot.domain.SyncRun;
import com.hapbang.auxiliary.chatbot.domain.SyncRunStatus;
import com.hapbang.auxiliary.chatbot.domain.SyncTrigger;
import com.hapbang.auxiliary.chatbot.domain.SyncType;
import com.hapbang.auxiliary.chatbot.out.SyncMismatchRepository;
import com.hapbang.auxiliary.chatbot.out.SyncRunRepository;
import com.hapbang.auxiliary.chatbot.support.ChatbotIntegrationTestBase;
import com.hapbang.shared.appearance.domain.AppearanceResult;
import com.hapbang.shared.appearance.dto.AppearanceSyncDto;
import com.hapbang.shared.appearance.dto.AppearanceSyncPageDto;

/** 싱크를 가짜 출연 원본과 실제 DB로 검증한다 (#716, #717) */
class ChatbotSyncUseCaseTest extends ChatbotIntegrationTestBase {

    @Autowired
    private ChatbotSyncUseCase chatbotSyncUseCase;

    @Autowired
    private CopyApplier copyApplier;

    @Autowired
    private SyncRunRepository syncRunRepository;

    @Autowired
    private SyncMismatchRepository syncMismatchRepository;

    @Test
    @DisplayName("첫 실행: 원본 전체를 넣고 성공한다. 경고하지 않고 어긋남 행도 남기지 않는다(건수만). 페이지를 넘어 끝까지 읽는다")
    void firstRun() {
        source.put(IntStream.rangeClosed(1, 1_201)
                .mapToObj(id -> appearance(id, AppearanceResult.SCHEDULED, 0)).toArray(AppearanceSyncDto[]::new));

        SyncRun run = run(SyncType.APPEARANCE);

        assertThat(run.getStatus()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(run.getScanned()).isEqualTo(1_201);
        assertThat(run.getInserted()).isEqualTo(1_201);
        assertThat(run.isWarning()).isFalse();
        assertThat(run.getFinishedAt()).isNotNull();
        assertThat(source.calls()).isEqualTo(3);
        assertThat(rows.appearanceCount()).isEqualTo(1_201);
        assertThat(mismatches(run)).isEmpty();
        assertThat(rows.appearance(1)).containsEntry("seen_run_id", run.getId());
    }

    @Test
    @DisplayName("두 번째 실행: 원본에만 있음·원본 새것·우리 새것·같은 version 다른 값을 기록하고, version만 오른 것은 정상으로 본다")
    void mismatches() {
        source.put(appearance(1, AppearanceResult.SCHEDULED, 0), appearance(2, AppearanceResult.SCHEDULED, 0),
                appearance(3, AppearanceResult.SCHEDULED, 0), appearance(4, AppearanceResult.SCHEDULED, 0),
                appearance(5, AppearanceResult.SCHEDULED, 0));
        run(SyncType.APPEARANCE);
        // 원본이 바뀌었고 이벤트를 놓쳤다
        source.put(appearance(2, AppearanceResult.COMPLETED, 1), appearance(3, AppearanceResult.SCHEDULED, 1),
                appearance(6, AppearanceResult.SCHEDULED, 0));
        // 이벤트가 먼저 들어와 우리가 더 새것
        copyApplier.apply(appearance(4, AppearanceResult.CANCELED, 2), null);
        // 같은 version인데 값이 다르다(잘못 반영됨)
        jdbcTemplate.update("UPDATE chatbot.appearance_copy SET result = 'NO_SHOW', dto_hash = repeat('0', 64) WHERE id = 5");

        SyncRun run = run(SyncType.APPEARANCE);

        assertThat(run.getStatus()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(run.getScanned()).isEqualTo(6);
        assertThat(run.getInserted()).isEqualTo(1);
        assertThat(run.getFixed()).isEqualTo(2);
        assertThat(run.getOursNewer()).isEqualTo(1);
        assertThat(mismatches(run)).extracting(SyncMismatch::getSourceId, SyncMismatch::getKind,
                        SyncMismatch::getOurVersion, SyncMismatch::getSourceVersion)
                .containsExactly(
                        tuple(2L, SyncMismatchKind.SOURCE_NEWER, 0L, 1L),
                        tuple(4L, SyncMismatchKind.OURS_NEWER, 2L, 0L),
                        tuple(5L, SyncMismatchKind.SAME_VERSION_DIFFERENT, 0L, 0L),
                        tuple(6L, SyncMismatchKind.ONLY_IN_SOURCE, null, 0L));
        assertThat(rows.appearance(2)).containsEntry("result", "COMPLETED");
        assertThat(rows.appearance(3)).containsEntry("version", 1L);
        assertThat(rows.appearance(4)).containsEntry("result", "CANCELED");
        assertThat(rows.appearance(5)).containsEntry("result", "SCHEDULED");
    }

    @Test
    @DisplayName("원본 없음: 성공한 회차에서 보지 못한 행만 표시하고, 스캔 중 이벤트로 들어온 행은 빼며, 다시 들어오면 푼다")
    void missingInSource() {
        source.put(appearance(1, AppearanceResult.SCHEDULED, 0), appearance(2, AppearanceResult.SCHEDULED, 0));
        run(SyncType.APPEARANCE);
        source.remove(2);
        clock.advance(Duration.ofDays(7));
        // 스캔 중(첫 페이지 뒤) 이벤트로 새 출연이 들어온다. 원본 목록에는 아직 없다
        source.afterFirstPage(() -> {
            clock.advance(Duration.ofSeconds(1));
            copyApplier.apply(appearance(9, AppearanceResult.SCHEDULED, 0), null);
        });
        // 첫 페이지 뒤에 다음 페이지가 있도록 원본을 늘린다
        source.put(IntStream.rangeClosed(10, 510)
                .mapToObj(id -> appearance(id, AppearanceResult.SCHEDULED, 0)).toArray(AppearanceSyncDto[]::new));

        SyncRun run = run(SyncType.APPEARANCE);

        assertThat(run.getStatus()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(run.getMarkedMissing()).isEqualTo(1);
        assertThat(rows.missingAppearances()).containsExactly(2L);
        // 어긋남 행(MISSING_IN_SOURCE)은 reviews()가 본다. 여기서는 넣은 501건이 회차당 최대 100건을 먼저 채운다

        // 원본에 다시 생기면 표시를 푼다. 스캔 중에 들어왔던 9는 이번 회차도 원본에 없어 이제 표시한다
        source.put(appearance(2, AppearanceResult.SCHEDULED, 0));
        clock.advance(Duration.ofDays(7));
        run(SyncType.APPEARANCE);
        assertThat(rows.missingAppearances()).containsExactly(9L);
    }

    @Test
    @DisplayName("실패한 회차는 원본 없음을 표시하지 않고, 그때까지 반영한 건수와 이유를 남긴다")
    void failedRunDoesNotMark() {
        source.put(appearance(1, AppearanceResult.SCHEDULED, 0), appearance(2, AppearanceResult.SCHEDULED, 0));
        run(SyncType.APPEARANCE);
        clock.advance(Duration.ofDays(7));
        source.remove(2);
        source.put(IntStream.rangeClosed(10, 600)
                .mapToObj(id -> appearance(id, AppearanceResult.SCHEDULED, 0)).toArray(AppearanceSyncDto[]::new));
        source.failAt(source.calls() + 2, new IllegalStateException("출연 앱 응답 없음"));

        SyncRun run = run(SyncType.APPEARANCE);

        assertThat(run.getStatus()).isEqualTo(SyncRunStatus.FAILED);
        assertThat(run.getScanned()).isEqualTo(500);
        assertThat(run.getError()).contains("출연 앱 응답 없음");
        assertThat(run.getMarkedMissing()).isZero();
        assertThat(rows.missingAppearances()).isEmpty();
    }

    @Test
    @DisplayName("원본 키셋 응답이 계약과 다르면(다음 키가 마지막 ID가 아님) 그 페이지를 반영하지 않고 실패한다")
    void keysetViolation() {
        source.put(appearance(1, AppearanceResult.SCHEDULED, 0), appearance(2, AppearanceResult.SCHEDULED, 0));
        source.tamper(page -> new AppearanceSyncPageDto(page.items(), 1L));

        SyncRun run = run(SyncType.APPEARANCE);

        assertThat(run.getStatus()).isEqualTo(SyncRunStatus.FAILED);
        assertThat(run.getError()).contains("nextAfterId");
        assertThat(rows.appearanceCount()).isZero();
    }

    @Test
    @DisplayName("경고: 첫 실행이 아니고 (넣은 + 고친) / 읽은 건수 ≥ 30%, 최소 10건 이상일 때만")
    void warning() {
        source.put(IntStream.rangeClosed(1, 30)
                .mapToObj(id -> appearance(id, AppearanceResult.SCHEDULED, 0)).toArray(AppearanceSyncDto[]::new));
        assertThat(run(SyncType.APPEARANCE).isWarning()).isFalse();

        // 9건 바뀜(30%지만 최소 10건 미만)
        source.put(IntStream.rangeClosed(1, 9)
                .mapToObj(id -> appearance(id, AppearanceResult.COMPLETED, 1)).toArray(AppearanceSyncDto[]::new));
        assertThat(run(SyncType.APPEARANCE).isWarning()).isFalse();

        // 10건 넣음 / 40건 = 25%
        source.put(IntStream.rangeClosed(31, 40)
                .mapToObj(id -> appearance(id, AppearanceResult.SCHEDULED, 0)).toArray(AppearanceSyncDto[]::new));
        assertThat(run(SyncType.APPEARANCE).isWarning()).isFalse();

        // 12건 넣음 / 52건 ≈ 23%, 고친 4건 더해 16 / 52 ≈ 30.7%
        source.put(IntStream.rangeClosed(41, 52)
                .mapToObj(id -> appearance(id, AppearanceResult.SCHEDULED, 0)).toArray(AppearanceSyncDto[]::new));
        source.put(IntStream.rangeClosed(10, 13)
                .mapToObj(id -> appearance(id, AppearanceResult.CANCELED, 1)).toArray(AppearanceSyncDto[]::new));
        SyncRun warned = run(SyncType.APPEARANCE);
        assertThat(warned.isWarning()).isTrue();
        assertThat(warned.getInserted() + warned.getFixed()).isEqualTo(16);
    }

    @Test
    @DisplayName("공개된 리뷰 싱크: 넣고, 값이 다르면 고치고, 원본에 없으면 표시한다")
    void reviews() {
        source.putReviews(review(10, 1, 5), review(11, 1, 4));
        SyncRun first = run(SyncType.APPEARANCE_REVIEW);
        assertThat(first.getStatus()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(first.getInserted()).isEqualTo(2);

        jdbcTemplate.update("UPDATE chatbot.appearance_review_copy SET rating = 1, dto_hash = repeat('0', 64) "
                + "WHERE review_id = 10");
        source.reset();
        source.putReviews(review(10, 1, 5));
        clock.advance(Duration.ofDays(7));
        SyncRun second = run(SyncType.APPEARANCE_REVIEW);

        assertThat(second.getFixed()).isEqualTo(1);
        assertThat(rows.review(10)).containsEntry("rating", 5);
        assertThat(rows.missingReviews()).containsExactly(11L);
        assertThat(mismatches(second)).extracting(SyncMismatch::getSourceId, SyncMismatch::getKind,
                        SyncMismatch::getOurVersion, SyncMismatch::getSourceVersion)
                .containsExactly(tuple(10L, SyncMismatchKind.SAME_VERSION_DIFFERENT, null, null),
                        tuple(11L, SyncMismatchKind.MISSING_IN_SOURCE, null, null));
    }

    @Test
    @DisplayName("정기 배치는 그 종류만 실행해 기록하고, 실패해도 예외를 던지지 않는다. 다른 종류는 제 시각에 그대로 돈다")
    void scheduledRunsOneType() {
        source.put(appearance(1, AppearanceResult.SCHEDULED, 0));
        source.putReviews(review(10, 1, 5));
        source.failAt(1, new IllegalStateException("출연 실패"));

        chatbotSyncUseCase.runScheduled(SyncType.APPEARANCE);
        chatbotSyncUseCase.runScheduled(SyncType.APPEARANCE_REVIEW);

        assertThat(syncRunRepository.findAll()).extracting(SyncRun::getType, SyncRun::getTrigger, SyncRun::getStatus)
                .containsExactlyInAnyOrder(
                        tuple(SyncType.APPEARANCE, SyncTrigger.SCHEDULED, SyncRunStatus.FAILED),
                        tuple(SyncType.APPEARANCE_REVIEW, SyncTrigger.SCHEDULED, SyncRunStatus.SUCCEEDED));
        // 실패한 종류는 다음 정시가 다시 한다
        clock.advance(Duration.ofHours(1));
        chatbotSyncUseCase.runScheduled(SyncType.APPEARANCE);
        assertThat(syncRunRepository.findAll()).filteredOn(run -> run.getType() == SyncType.APPEARANCE)
                .extracting(SyncRun::getStatus).containsExactlyInAnyOrder(SyncRunStatus.FAILED, SyncRunStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("보관 기간(30일)이 지난 회차는 그 종류의 회차가 끝날 때 어긋남 행과 함께 지운다. 다른 종류와 실행 중 회차는 남긴다")
    void deletesExpiredRuns() {
        source.put(appearance(1, AppearanceResult.SCHEDULED, 0));
        SyncRun first = run(SyncType.APPEARANCE);
        source.put(appearance(2, AppearanceResult.SCHEDULED, 0));
        clock.advance(Duration.ofHours(1));
        SyncRun old = run(SyncType.APPEARANCE);
        SyncRun otherType = syncRunRepository.save(
                SyncRun.skipped(SyncType.APPEARANCE_REVIEW, SyncTrigger.SCHEDULED, "테스트", CopyTimes.now(clock)));
        assertThat(mismatches(old)).isNotEmpty();

        clock.advance(Duration.ofDays(30).plusMinutes(1));
        SyncRun recent = run(SyncType.APPEARANCE);

        assertThat(syncRunRepository.findAll()).extracting(SyncRun::getId)
                .containsExactlyInAnyOrder(otherType.getId(), recent.getId());
        assertThat(mismatches(old)).isEmpty();
        assertThat(syncRunRepository.findById(first.getId())).isEmpty();
    }

    @Test
    @DisplayName("어긋남 행은 회차당 최대 개수까지만, 종류마다 최근 회차 몇 개만 남는다(회차 기록은 남는다, 테스트 설정 10회)")
    void mismatchLimits() {
        source.put(appearance(1, AppearanceResult.SCHEDULED, 0));
        run(SyncType.APPEARANCE);
        List<SyncRun> runs = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            long from = 1_000L * (i + 1);
            source.put(IntStream.range(0, 101)
                    .mapToObj(n -> appearance(from + n, AppearanceResult.SCHEDULED, 0)).toArray(AppearanceSyncDto[]::new));
            clock.advance(Duration.ofDays(1));
            runs.add(run(SyncType.APPEARANCE));
        }

        assertThat(runs.getLast().getInserted()).isEqualTo(101);
        assertThat(mismatches(runs.getLast())).hasSize(100);
        // 최근 10회(첫 실행 제외 마지막 10회)만 남는다
        assertThat(mismatches(runs.getFirst())).isEmpty();
        assertThat(mismatches(runs.get(1))).hasSize(100);
        assertThat(syncRunRepository.count()).isEqualTo(12);

        // 건너뛴 회차는 최근 회차 수에 세지 않는다. 건너뜀이 이어져도 실제로 돈 회차의 기록이 남는다
        for (int i = 0; i < 5; i++) {
            clock.advance(Duration.ofHours(1));
            syncRunRepository.save(SyncRun.skipped(SyncType.APPEARANCE, SyncTrigger.SCHEDULED, "테스트", CopyTimes.now(clock)));
        }
        source.put(IntStream.range(0, 101)
                .mapToObj(n -> appearance(50_000L + n, AppearanceResult.SCHEDULED, 0)).toArray(AppearanceSyncDto[]::new));
        clock.advance(Duration.ofDays(1));
        runs.add(run(SyncType.APPEARANCE));

        assertThat(mismatches(runs.get(1))).isEmpty();
        assertThat(mismatches(runs.get(2))).hasSize(100);
    }

    @Test
    @DisplayName("끝을 기록하지 못해 RUNNING으로 남은 회차는 그 종류의 다음 실행이 실패로 닫는다. 다른 종류는 건드리지 않는다")
    void closesOrphanRuns() {
        SyncRun orphan = syncRunRepository.save(
                SyncRun.start(SyncType.APPEARANCE, SyncTrigger.SCHEDULED, null, CopyTimes.now(clock)));
        SyncRun otherType = syncRunRepository.save(
                SyncRun.start(SyncType.APPEARANCE_REVIEW, SyncTrigger.SCHEDULED, null, CopyTimes.now(clock)));
        source.put(appearance(1, AppearanceResult.SCHEDULED, 0));
        clock.advance(Duration.ofDays(7));

        assertThat(run(SyncType.APPEARANCE).getStatus()).isEqualTo(SyncRunStatus.SUCCEEDED);

        SyncRun closed = syncRunRepository.findById(orphan.getId()).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(SyncRunStatus.FAILED);
        assertThat(closed.getError()).contains("끝을 기록하지 못했습니다");
        assertThat(closed.getFinishedAt()).isNotNull();
        assertThat(syncRunRepository.findById(otherType.getId()).orElseThrow().getStatus())
                .isEqualTo(SyncRunStatus.RUNNING);
    }

    private SyncRun run(SyncType type) {
        return chatbotSyncUseCase.runBatch(type, SyncTrigger.SCHEDULED);
    }

    private List<SyncMismatch> mismatches(SyncRun run) {
        return syncMismatchRepository.findByRunIdOrderByIdAsc(run.getId());
    }
}
