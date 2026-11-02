package com.hapbang.lecture.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 강의 이벤트 Kafka 재발행 설정 ({@code lecture.event-republish.*}, #278). 값을 주지 않으면 기본값이다.
 * 배치 주기는 {@code lecture.event-republish.cron}(기본 매분 5초)으로 {@code LectureEventRepublishBatch}가 읽는다.
 *
 * @param pendingGrace 발행한 뒤 이 시간이 지난 미완료 기록만 다시 보낸다. 커밋 직후 보내는 중인 기록과 겹치지 않게 한다. 기본 1분
 * @param batchSize    한 번에 다시 보낼 최대 건수. 기본 100
 */
@ConfigurationProperties("lecture.event-republish")
public record LectureEventRepublishProperties(Duration pendingGrace, Integer batchSize) {

    public LectureEventRepublishProperties {
        pendingGrace = pendingGrace == null ? Duration.ofMinutes(1) : pendingGrace;
        batchSize = batchSize == null ? 100 : batchSize;
    }
}
