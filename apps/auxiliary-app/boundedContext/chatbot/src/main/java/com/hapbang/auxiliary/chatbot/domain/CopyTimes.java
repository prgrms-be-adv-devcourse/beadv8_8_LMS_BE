package com.hapbang.auxiliary.chatbot.domain;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * 원본이 준 시각을 사본에 넣을 KST {@code LocalDateTime}(마이크로초까지)으로 맞춘다 (#716).
 * <p>
 * 원본마다 형식이 다르다(#712·#713 병합 뒤 확인). 출연은 offset 없는 KST {@code LocalDateTime}, 강의는 {@code +09:00}이 붙은
 * {@code OffsetDateTime}이다. 해시와 저장 모두 이 값으로 해서, 같은 시각이면 어느 형식으로 받아도 해시가 같다.
 * DB 컬럼은 {@code TIMESTAMP(6)}이라 마이크로초까지만 둔다.
 */
public final class CopyTimes {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private CopyTimes() {
    }

    /** offset 없는 값은 이미 KST다 */
    public static LocalDateTime kst(LocalDateTime value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MICROS);
    }

    /** offset이 붙은 값은 KST로 옮긴 뒤 offset을 뗀다 */
    public static LocalDateTime kst(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(KST).toLocalDateTime().truncatedTo(ChronoUnit.MICROS);
    }

    /** 사본·회차에 적는 현재 시각. 컬럼과 같은 마이크로초까지 둔다 */
    public static LocalDateTime now(Clock clock) {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
