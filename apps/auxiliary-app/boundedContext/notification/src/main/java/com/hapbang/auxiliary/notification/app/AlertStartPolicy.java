package com.hapbang.auxiliary.notification.app;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * 알림 시작 시각(ND-076). 업무 시각(ND-021, 없으면 Kafka 레코드 timestamp ND-081)이 이 시각보다 이른 사건은 알림을 만들지 않고 커밋한다.
 * 첫 배포 때 보존 기간(72시간) 안의 과거 사건이 한꺼번에 알림이 되지 않게 한다. 새 consumer group의 시작점은 팀 규칙대로
 * {@code earliest}다(kafka.md "소비").
 * <p>
 * 시각 정하기(ND-095):
 * <ul>
 *     <li>설정 {@value #PROPERTY}(시간대가 있는 ISO-8601)가 있으면 그 값을 쓴다(테스트·로컬, 비상 시 덮어쓰기). 읽을 수 없으면 기동에
 *     실패한다(ND-088).</li>
 *     <li>없으면(운영) 앱이 처음 뜰 때 {@link AlertStartStore}에 지금 시각을 한 번 기록하고 기록된 값을 쓴다. 재배포·재시작에도 처음
 *     값이다. 이 빈을 만들 때 기록하므로 Kafka 소비자(회원 복제본 리스너 포함)가 뜨기 전에 값이 정해진다.</li>
 * </ul>
 */
@Slf4j
@Component
public class AlertStartPolicy {

    public static final String PROPERTY = "hapbang.notification.alert.start-at";
    /** 기록 저장소의 키 */
    static final String STORE_KEY = "alert.start-at";

    private final Instant startAt;

    @Autowired
    public AlertStartPolicy(@Value("${" + PROPERTY + ":}") String configured, AlertStartStore store) {
        this(resolve(configured, store));
    }

    public AlertStartPolicy(Instant startAt) {
        Assert.notNull(startAt, "알림 시작 시각이 필요합니다.");
        this.startAt = startAt;
    }

    private static Instant resolve(String configured, AlertStartStore store) {
        if (StringUtils.hasText(configured)) {
            Instant parsed = parse(configured);
            log.info("알림 시작 시각을 설정값으로 정했다 startAt={}, property={}", parsed, PROPERTY);
            return parsed;
        }
        Instant recorded = store.recordIfAbsent(STORE_KEY, Instant.now());
        log.info("알림 시작 시각을 기록된 첫 기동 시각으로 정했다 startAt={}", recorded);
        return recorded;
    }

    private static Instant parse(String value) {
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
