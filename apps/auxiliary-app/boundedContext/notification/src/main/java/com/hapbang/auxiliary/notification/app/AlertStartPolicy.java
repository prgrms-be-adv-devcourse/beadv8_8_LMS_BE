package com.hapbang.auxiliary.notification.app;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/**
 * 알림 시작 시각(ND-076). 업무 시각(ND-021, 없으면 Kafka 레코드 timestamp ND-081)이 이 시각보다 이른 사건은 알림을 만들지 않고 커밋한다.
 * 첫 배포 때 보존 기간(72시간) 안의 과거 사건이 한꺼번에 알림이 되지 않게 한다. 새 consumer group의 시작점은 팀 규칙대로
 * {@code earliest}다(kafka.md "소비").
 * <p>
 * 값({@value #PROPERTY}, 시간대가 있는 ISO-8601)은 필수다. 기본값을 두지 않아 빠뜨리거나 잘못 쓰면 기동에 실패한다. 운영 값은
 * {@code application-prod.yaml}에 두고, 첫 운영 배포의 알림 활성화 기준 시각이며(U20) 한 번 적용하면 바꾸지 않는다(ND-088).
 */
@Component
public class AlertStartPolicy {

    public static final String PROPERTY = "hapbang.notification.alert.start-at";

    private final Instant startAt;

    /**
     * 설정 문자열을 직접 읽는다(Spring 변환 서비스에 기대지 않는다). ISO-8601 시각({@code 2026-11-11T13:00:00+09:00},
     * {@code ...Z})만 받는다. 값이 없거나 읽을 수 없으면 기동에 실패한다(ND-088)
     */
    @Autowired
    public AlertStartPolicy(@Value("${" + PROPERTY + "}") String startAt) {
        this(parse(startAt));
    }

    public AlertStartPolicy(Instant startAt) {
        Assert.notNull(startAt, "알림 시작 시각(" + PROPERTY + ")이 필요합니다.");
        this.startAt = startAt;
    }

    private static Instant parse(String value) {
        Assert.hasText(value, "알림 시작 시각(" + PROPERTY + ")이 필요합니다.");
        try {
            return OffsetDateTime.parse(value.strip()).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "알림 시작 시각(" + PROPERTY + ")은 시간대가 있는 ISO-8601 시각이어야 합니다: " + value, e);
        }
    }

    /** 이 사건으로 알림을 만들어도 되는지. 시작 시각과 같으면 만든다 */
    public boolean allows(Instant occurredAt) {
        return !occurredAt.isBefore(startAt);
    }

    public Instant startAt() {
        return startAt;
    }
}
