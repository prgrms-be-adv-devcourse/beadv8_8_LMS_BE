package com.hapbang.auxiliary.search.common.in;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.global.scheduling.ConditionalOnSchedulingEnabled;
import com.hapbang.auxiliary.search.common.app.SynonymsCheck;
import com.hapbang.auxiliary.search.config.SearchConfig;

/**
 * 동의어 묶음 확인 배치(#895). 실행 시각만 정하고 처리는 {@link SynonymsCheck}가 한다. 모든 대상이 함께 쓰는 묶음이라 대상 배치가 아니라 여기에 둔다.
 * 기동 직후 ES 연결을 기다려 1분 뒤에 시작하고, 실행기에 넘긴 뒤 10분마다 다시 한다. 같은 pod에서 앞 실행이 아직 돌고 있으면 락이 건너뛴다.
 * <p>
 * ES를 부르므로 재색인 확인({@code SearchBatch.checkIndex})처럼 재색인 실행기({@link SearchConfig#REINDEX_EXECUTOR})로 넘긴다. ES가 느리면
 * 앱 공용 스케줄러 스레드(2개)를 붙잡아 챗봇·알림 배치가 밀린다(#895 리뷰).
 */
@Component
@ConditionalOnSchedulingEnabled
class SynonymsCheckBatch {

    private final SynonymsCheck synonymsCheck;
    private final TaskExecutor reindexExecutor;

    SynonymsCheckBatch(SynonymsCheck synonymsCheck,
            @Qualifier(SearchConfig.REINDEX_EXECUTOR) TaskExecutor reindexExecutor) {
        this.synonymsCheck = synonymsCheck;
        this.reindexExecutor = reindexExecutor;
    }

    @Scheduled(fixedDelayString = "${hapbang.search.synonyms-check-interval:10m}", initialDelayString = "1m")
    void checkSynonyms() {
        reindexExecutor.execute(synonymsCheck::run);
    }
}
