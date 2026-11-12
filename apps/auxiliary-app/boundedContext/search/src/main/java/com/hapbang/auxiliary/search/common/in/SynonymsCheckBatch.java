package com.hapbang.auxiliary.search.common.in;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.global.scheduling.ConditionalOnSchedulingEnabled;
import com.hapbang.auxiliary.search.common.app.SynonymsCheck;

/**
 * 동의어 묶음 확인 배치(#895). 실행 시각만 정하고 처리는 {@link SynonymsCheck}가 한다. 모든 대상이 함께 쓰는 묶음이라 대상 배치가 아니라 여기에 둔다.
 * 기동 직후 ES 연결을 기다려 1분 뒤에 시작하고, 끝난 뒤 10분 쉬고 다시 한다.
 */
@Component
@ConditionalOnSchedulingEnabled
class SynonymsCheckBatch {

    private final SynonymsCheck synonymsCheck;

    SynonymsCheckBatch(SynonymsCheck synonymsCheck) {
        this.synonymsCheck = synonymsCheck;
    }

    @Scheduled(fixedDelayString = "${hapbang.search.synonyms-check-interval:10m}", initialDelayString = "1m")
    void checkSynonyms() {
        synonymsCheck.run();
    }
}
