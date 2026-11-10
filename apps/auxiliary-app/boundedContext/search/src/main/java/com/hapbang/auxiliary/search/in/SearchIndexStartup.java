package com.hapbang.auxiliary.search.in;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.search.app.InfluencerReindexer;
import com.hapbang.auxiliary.search.config.ConditionalOnSearchEnabled;

import lombok.RequiredArgsConstructor;

/**
 * 앱이 뜬 뒤 검색 index를 맞춘다: 동의어 묶음, 필요하면 처음 적재·재색인(#771, #773).
 * <ul>
 *     <li>별도 스레드에서 돈다. ES·회원 앱이 느리거나 죽어도, 처음 적재가 오래 걸려도 기동과 다른 모듈(챗봇·알림)을 막지 않는다.</li>
 *     <li>한 pod만 한다({@link InfluencerReindexer#run}, advisory lock). 실패하면 10분마다 하는 확인({@code SearchBatch})이 다시 한다.</li>
 * </ul>
 * 모듈 테스트는 {@code hapbang.search.setup-on-startup=false}로 끈다. 테스트 클래스들이 ES 컨테이너 하나를 차례로 비우고 쓰는데, 컨텍스트가
 * 뜰 때 다른 스레드가 index를 만들면 다음 테스트와 겹친다.
 */
@Component
@ConditionalOnSearchEnabled
@ConditionalOnBooleanProperty(name = "hapbang.search.setup-on-startup", matchIfMissing = true)
@RequiredArgsConstructor
class SearchIndexStartup {

    private final InfluencerReindexer reindexer;

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        Thread.ofVirtual().name("search-index-setup").start(reindexer::run);
    }
}
