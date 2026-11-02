package com.hapbang.lecture.app;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.lecture.global.scheduling.ConditionalOnSchedulingEnabled;

import lombok.RequiredArgsConstructor;

/** 강의 이벤트 Kafka 재발행 배치. 기본 매분 5초에 실행한다 (#278). 처리는 {@link LectureEventRepublishUseCase}가 한다 */
@Component
@ConditionalOnSchedulingEnabled
@RequiredArgsConstructor
class LectureEventRepublishBatch {

    private final LectureEventRepublishUseCase lectureEventRepublishUseCase;

    @Scheduled(cron = "${lecture.event-republish.cron:5 * * * * *}", zone = "Asia/Seoul")
    void run() {
        lectureEventRepublishUseCase.republishDue();
    }
}
