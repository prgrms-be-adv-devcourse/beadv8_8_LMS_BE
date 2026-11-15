package com.hapbang.auxiliary.chatbot.app;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.domain.SyncType;
import com.hapbang.auxiliary.chatbot.out.AppearanceReviewCopyStore;
import com.hapbang.auxiliary.chatbot.out.AppearanceSyncSource;
import com.hapbang.auxiliary.chatbot.out.CopyWrite;
import com.hapbang.auxiliary.chatbot.out.MissingCopy;
import com.hapbang.shared.appearance.dto.AppearanceReviewSyncDto;
import com.hapbang.shared.appearance.dto.AppearanceReviewSyncPageDto;

import lombok.RequiredArgsConstructor;

/** 공개된 출연 리뷰 싱크 (#716). 출연 내부 API {@code GET /api/v1/internal/appearances/sync/reviews} */
@Component
@RequiredArgsConstructor
class AppearanceReviewSyncTarget implements SyncTarget<AppearanceReviewSyncDto> {

    private final AppearanceSyncSource source;
    private final CopyApplier copyApplier;
    private final AppearanceReviewCopyStore store;

    @Override
    public SyncType type() {
        return SyncType.APPEARANCE_REVIEW;
    }

    @Override
    public ChatbotJob job() {
        return ChatbotJob.APPEARANCE_REVIEW_SYNC;
    }

    @Override
    public Page<AppearanceReviewSyncDto> fetch(Long afterId, int size) {
        AppearanceReviewSyncPageDto page = source.reviews(afterId, size);
        return new Page<>(page.items(), page.nextAfterId());
    }

    @Override
    public long idOf(AppearanceReviewSyncDto item) {
        return item.reviewId();
    }

    @Override
    public Long versionOf(AppearanceReviewSyncDto item) {
        return null;
    }

    @Override
    public CopyWrite apply(AppearanceReviewSyncDto item, long runId) {
        return copyApplier.apply(item, runId);
    }

    @Override
    public List<MissingCopy> markMissing(long runId, LocalDateTime startedAt) {
        return store.markMissing(runId, startedAt);
    }
}
