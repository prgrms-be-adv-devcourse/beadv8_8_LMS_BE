package com.hapbang.auxiliary.search.app;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.global.scheduling.JobLock;
import com.hapbang.auxiliary.search.config.ConditionalOnSearchEnabled;
import com.hapbang.auxiliary.search.out.IndexNotReadyException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 검색 작업을 advisory lock({@link JobLock})으로 한 pod에서만 실행한다(#771). 챗봇 {@code FaqJobRunner}와 같은 방식이다.
 * 락을 잡지 못하면 그 회차를 건너뛴다. 작업이 실패하면 WARN을 남기고 다음 회차를 기다린다(배치 스레드를 죽이지 않는다).
 * alias가 없어(처음 적재 전) 못 한 회차는 INFO로 건너뛴다(#773 Q1).
 */
@Slf4j
@Component
@ConditionalOnSearchEnabled
@RequiredArgsConstructor
public class SearchJobRunner {

    private final JobLock jobLock;

    /** @return 실행했으면 true(성공·실패 무관), 락 때문에 건너뛰었으면 false */
    public boolean run(SearchJob job, Runnable task) {
        Optional<JobLock.Lease> lease;
        try {
            lease = jobLock.tryAcquire(job);
        } catch (JobLock.JobLockException e) {
            log.warn("실행 락을 시도하지 못해 건너뛴다 job={}", job.tag(), e);
            return false;
        }
        if (lease.isEmpty()) {
            log.info("다른 실행이 락을 쥐고 있어 건너뛴다 job={}", job.tag());
            return false;
        }
        try (JobLock.Lease ignored = lease.get()) {
            task.run();
        } catch (IndexNotReadyException e) {
            // 처음 적재 전이다. 실패가 아니라 할 일이 없다(#773 Q1)
            log.info("검색 index를 처음 적재하기 전이라 이번 회차를 건너뛴다 job={}", job.tag());
        } catch (RuntimeException e) {
            log.warn("검색 작업이 실패해 다음 회차에 다시 한다 job={}", job.tag(), e);
        }
        return true;
    }
}
