package com.hapbang.config;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.Scheduled;

import com.hapbang.global.scheduling.ConditionalOnSchedulingEnabled;

/** 스케줄링 테스트용 배치. 실행될 때마다 실행한 스레드 이름을 기록한다 */
@TestConfiguration(proxyBeanMethods = false)
@Import(SchedulingProbe.ProbeBatch.class)
class SchedulingProbe {

    @ConditionalOnSchedulingEnabled
    static class ProbeBatch {

        final List<String> threadNames = new CopyOnWriteArrayList<>();

        @Scheduled(fixedDelay = 100)
        void run() {
            threadNames.add(Thread.currentThread().getName());
        }
    }
}
