package com.hapbang.auxiliary.search.in;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.search.config.ConditionalOnSearchEnabled;
import com.hapbang.auxiliary.search.out.InfluencerIndexSetup;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 앱이 뜬 뒤 검색 동의어 묶음과 index·alias를 맞춘다(#771).
 * <ul>
 *     <li>별도 스레드에서 돈다. ES가 느리거나 죽어도 기동과 다른 모듈(챗봇·알림)을 막지 않는다.</li>
 *     <li>실패해도 앱은 그대로다. 이벤트 소비·통계 동기화가 쓰기 전에 다시 맞춘다({@link InfluencerIndexSetup#ensureReady()}).</li>
 *     <li>pod 여러 대가 함께 해도 결과가 같아 락을 잡지 않는다.</li>
 * </ul>
 * 모듈 테스트는 {@code hapbang.search.setup-on-startup=false}로 끈다. 테스트 클래스들이 ES 컨테이너 하나를 차례로 비우고 쓰는데, 컨텍스트가
 * 뜰 때 다른 스레드가 index를 만들면 다음 테스트와 겹친다.
 */
@Slf4j
@Component
@ConditionalOnSearchEnabled
@ConditionalOnBooleanProperty(name = "hapbang.search.setup-on-startup", matchIfMissing = true)
@RequiredArgsConstructor
class SearchIndexStartup {

    private final InfluencerIndexSetup indexSetup;

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        Thread.ofVirtual().name("search-index-setup").start(this::run);
    }

    void run() {
        try {
            indexSetup.ensureReady();
        } catch (RuntimeException e) {
            log.warn("시작할 때 검색 index를 준비하지 못했다. 이벤트 소비·통계 동기화 때 다시 한다", e);
        }
    }
}
