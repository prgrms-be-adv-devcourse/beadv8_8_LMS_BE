package com.hapbang.auxiliary.chatbot.in;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.app.ChatbotJob;
import com.hapbang.auxiliary.global.scheduling.JobLock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * FAQ 맞추기(기동 시·주기 점검)를 실행 락({@link ChatbotJob#FAQ_SYNC}) 안에서 돌린다(#715).
 * 락을 쥔 다른 실행이 있거나(배포 중 겹친 pod) 락을 시도하지 못하면 이번 회차를 건너뛴다. {@link JobLock}은 지표만 세므로 로그는 여기서 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class FaqJobRunner {

    private final JobLock jobLock;

    void run(String what, Runnable task) {
        Optional<JobLock.Lease> lease;
        try {
            lease = jobLock.tryAcquire(ChatbotJob.FAQ_SYNC);
        } catch (JobLock.JobLockException e) {
            log.warn("실행 락을 시도하지 못해 건너뛴다 job={}, what={}", ChatbotJob.FAQ_SYNC.tag(), what, e);
            return;
        }
        if (lease.isEmpty()) {
            log.info("다른 실행이 락을 쥐고 있어 건너뛴다 job={}, what={}", ChatbotJob.FAQ_SYNC.tag(), what);
            return;
        }
        try (JobLock.Lease ignored = lease.get()) {
            task.run();
        }
    }
}
