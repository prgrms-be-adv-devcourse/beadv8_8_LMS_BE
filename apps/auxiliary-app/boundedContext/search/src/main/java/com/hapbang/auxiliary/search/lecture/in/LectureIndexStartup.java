package com.hapbang.auxiliary.search.lecture.in;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.search.config.SearchConfig;
import com.hapbang.auxiliary.search.lecture.app.LectureReindexer;

/**
 * 앱이 뜬 뒤 강의 검색 index를 맞춘다: 동의어 묶음, 필요하면 처음 적재·재색인(#884). 인플루언서 {@code SearchIndexStartup}과 같은 방식이다.
 * <ul>
 *     <li>재색인 실행기({@link SearchConfig#REINDEX_EXECUTOR}, 가상 스레드)에서 돈다. 강의·회원 앱이 느리거나 처음 적재가 오래 걸려도 기동과 다른
 *     모듈을 막지 않는다. 인플루언서 처음 적재와는 락이 달라 함께 돈다.</li>
 *     <li>한 pod만 한다({@link LectureReindexer#run}, advisory lock). 실패하면 10분마다 하는 확인({@code LectureSearchBatch})이 다시 한다.</li>
 * </ul>
 * 모듈 테스트는 {@code hapbang.search.setup-on-startup=false}로 끈다(인플루언서와 같은 이유).
 */
@Component
@ConditionalOnBooleanProperty(name = "hapbang.search.setup-on-startup", matchIfMissing = true)
class LectureIndexStartup {

    private final LectureReindexer reindexer;
    private final TaskExecutor reindexExecutor;

    LectureIndexStartup(LectureReindexer reindexer,
            @Qualifier(SearchConfig.REINDEX_EXECUTOR) TaskExecutor reindexExecutor) {
        this.reindexer = reindexer;
        this.reindexExecutor = reindexExecutor;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        reindexExecutor.execute(reindexer::run);
    }
}
