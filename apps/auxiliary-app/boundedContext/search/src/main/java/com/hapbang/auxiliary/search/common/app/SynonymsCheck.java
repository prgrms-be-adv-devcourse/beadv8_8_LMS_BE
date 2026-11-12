package com.hapbang.auxiliary.search.common.app;

import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.search.common.out.SynonymsRejectedException;
import com.hapbang.auxiliary.search.common.out.SynonymsSetup;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 동의어 묶음 확인(#895, #868 R9). 묶음은 cluster에 하나이고 모든 대상이 함께 쓰므로, 거절을 알리는 일은 대상 수와 상관없이 여기 한 곳에서 한다.
 * 10분마다 한 pod에서만 돈다({@link SearchJob#SYNONYMS_CHECK}).
 * <ul>
 *     <li>ES의 묶음을 레포 파일과 다시 맞춘다({@link SynonymsSetup#resync}).</li>
 *     <li>ES가 묶음을 내용 때문에 거절하면 {@code [SEARCH_SYNONYMS_REJECTED]} ERROR와 지표 {@code hapbang.search.synonyms.rejected}를 남긴다.
 *     파일이 틀려 기다려도 풀리지 않으므로 사람이 {@code synonyms.txt}를 고쳐 배포해야 한다.</li>
 *     <li>ES에 닿지 못하거나 5xx·429면 실행기({@link SearchJobRunner})가 WARN을 남기고 다음 회차에 다시 한다.</li>
 * </ul>
 * 대상마다 재색인({@link Reindexer})도 index를 만들기 전에 묶음을 맞춘다. 그쪽은 묶음이 <b>있는지</b>만 보장하고(pod가 떠 있는 동안 ES를 새로
 * 만들면 묶음이 없다), 거절되면 WARN만 남기고 건너뛴다. 알리는 일은 여기만 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SynonymsCheck {

    /** 사람이 봐야 하는 실패의 로그 표식({@code docs/dev/logging.md} alert 표식) */
    static final String REJECTED_MARKER = "[SEARCH_SYNONYMS_REJECTED]";
    static final String REJECTED_METRIC = "hapbang.search.synonyms.rejected";

    private final SynonymsSetup synonyms;
    private final SearchJobRunner jobRunner;
    private final MeterRegistry registry;

    /** 한 pod에서만 실행한다. 다른 pod가 하고 있으면 건너뛴다 */
    public void run() {
        jobRunner.run(SearchJob.SYNONYMS_CHECK, this::check);
    }

    /**
     * 락 없이 한 번 확인한다. 바깥에서는 {@link #run}만 부른다(테스트가 바로 부를 수 있게 열어 둔다).
     *
     * @return 묶음이 파일과 같으면 true, ES가 거절했으면 false
     * @throws IllegalStateException ES에 닿지 못하거나 5xx·429인 경우(기다리면 풀린다)
     */
    boolean check() {
        try {
            synonyms.resync();
            return true;
        } catch (SynonymsRejectedException e) {
            // 10분마다 같은 이유로 실패한다. 처음 적재 전이면 모든 대상의 검색이 계속 503이다(PR #845 재리뷰)
            registry.counter(REJECTED_METRIC).increment();
            log.error("{} ES가 검색 동의어 묶음을 거절해 모든 대상의 처음 적재·재색인을 하지 않는다. synonyms.txt를 고쳐 배포한다",
                    REJECTED_MARKER, e);
            return false;
        }
    }
}
