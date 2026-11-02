package com.hapbang.customersupport.in;

import java.time.LocalDateTime;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.customersupport.app.LectureObjectionFiler;
import com.hapbang.customersupport.global.scheduling.ConditionalOnSchedulingEnabled;

import lombok.RequiredArgsConstructor;

/** 이의 제기 접수 재시도 배치. 기본 매분 15초에 실행한다 (#279). 처리는 {@link LectureObjectionFiler#retryDue}가 한다 */
@Component
@ConditionalOnSchedulingEnabled
@RequiredArgsConstructor
class ObjectionFilingRetryBatch {

    private final LectureObjectionFiler lectureObjectionFiler;

    @Scheduled(cron = "${customersupport.batch.filing-retry-cron:15 * * * * *}", zone = "Asia/Seoul")
    void run() {
        lectureObjectionFiler.retryDue(LocalDateTime.now());
    }
}
