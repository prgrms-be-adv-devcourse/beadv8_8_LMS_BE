package com.hapbang.auxiliary.chatbot.app;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.domain.SyncType;
import com.hapbang.auxiliary.chatbot.out.AppearanceCopyStore;
import com.hapbang.auxiliary.chatbot.out.AppearanceSyncSource;
import com.hapbang.auxiliary.chatbot.out.CopyWrite;
import com.hapbang.auxiliary.chatbot.out.MissingCopy;
import com.hapbang.shared.appearance.dto.AppearanceSyncDto;
import com.hapbang.shared.appearance.dto.AppearanceSyncPageDto;

import lombok.RequiredArgsConstructor;

/** 출연 싱크 (#716). 출연 내부 API {@code GET /api/v1/internal/appearances/sync/appearances} */
@Component
@RequiredArgsConstructor
class AppearanceSyncTarget implements SyncTarget<AppearanceSyncDto> {

    private final AppearanceSyncSource source;
    private final CopyApplier copyApplier;
    private final AppearanceCopyStore store;

    @Override
    public SyncType type() {
        return SyncType.APPEARANCE;
    }

    @Override
    public ChatbotJob job() {
        return ChatbotJob.APPEARANCE_SYNC;
    }

    @Override
    public Page<AppearanceSyncDto> fetch(Long afterId, int size) {
        AppearanceSyncPageDto page = source.appearances(afterId, size);
        return new Page<>(page.items(), page.nextAfterId());
    }

    @Override
    public long idOf(AppearanceSyncDto item) {
        return item.id();
    }

    @Override
    public Long versionOf(AppearanceSyncDto item) {
        return item.version();
    }

    @Override
    public CopyWrite apply(AppearanceSyncDto item, long runId) {
        return copyApplier.apply(item, runId);
    }

    @Override
    public List<MissingCopy> markMissing(long runId, LocalDateTime startedAt) {
        return store.markMissing(runId, startedAt);
    }
}
