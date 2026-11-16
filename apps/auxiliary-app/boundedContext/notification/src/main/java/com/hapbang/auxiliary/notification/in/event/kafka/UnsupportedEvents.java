package com.hapbang.auxiliary.notification.in.event.kafka;

import org.slf4j.Logger;

/**
 * 리스너의 기본 처리기({@code @KafkaHandler(isDefault = true)})가 받은 처리기 없는 레코드를 다룬다(ND-181). 건너뛸 수 있으면
 * {@link SkippedEventLog}로 남기고 커밋하고, 신뢰 패키지 밖 타입은 {@link RejectedEventException}으로 재시도 없이 DLT로 보낸다
 * ({@link SkippablePayloads}). 로그는 리스너의 logger로 남겨 로그 이름이 리스너 그대로다.
 */
public final class UnsupportedEvents {

    private final SkippedEventLog skippedLog;
    private final String trustedPackage;

    public UnsupportedEvents(Logger log, String trustedPackage) {
        this.skippedLog = new SkippedEventLog(log);
        this.trustedPackage = trustedPackage;
    }

    /**
     * 건너뛸 수 있는 레코드면 로그를 남기고 반환한다(offset 커밋).
     *
     * @throws RejectedEventException 신뢰 패키지 밖 타입(재시도 없이 DLT)
     */
    public void skipOrReject(Object payload, String key, Integer partition, Long offset) {
        skippedLog.skipped(SkippablePayloads.typeToSkip(payload, trustedPackage), key, partition, offset);
    }
}
