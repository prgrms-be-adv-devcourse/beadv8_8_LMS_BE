package com.hapbang.auxiliary.chatbot.app;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/** 시드 파일 → DB → 벡터 색인 순서로 FAQ를 맞춘다(#715). 한 pod만 돌도록 부르는 쪽이 {@code JobLock}으로 감싼다 */
@Service
@RequiredArgsConstructor
public class FaqSyncService {

    private final FaqSeedReader seedReader;
    private final FaqSeedSynchronizer seedSynchronizer;
    private final FaqIndexer indexer;

    /** 시드를 DB에 맞춘 뒤 색인한다. 색인이 실패해도 DB 반영은 남는다(다음 점검에서 색인만 다시 한다) */
    public void syncAll() {
        seedSynchronizer.sync(seedReader.read());
        indexer.sync();
    }
}
