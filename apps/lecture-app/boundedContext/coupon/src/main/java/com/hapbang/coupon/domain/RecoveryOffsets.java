package com.hapbang.coupon.domain;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Kafka partition별 offset 묶음. 복구 경계(재발행 직후 토픽의 끝 offset)를 recovery_run에 문자열로 남기고, 결과 확정 때 소비 그룹의 커밋
 * offset과 비교해 경계까지 남은 메시지 수(lag)를 센다. 문자열은 {@code partition:offset}을 partition 순서로 쉼표로 잇는다(예: {@code 0:12,1:0,2:7}).
 *
 * @param offsets partition → offset
 */
public record RecoveryOffsets(Map<Integer, Long> offsets) {

    public RecoveryOffsets {
        offsets = Map.copyOf(Objects.requireNonNull(offsets, "offsets"));
        offsets.forEach((partition, offset) -> {
            if (partition < 0 || offset < 0) {
                throw new IllegalArgumentException("partition·offset은 음수일 수 없다");
            }
        });
    }

    public static RecoveryOffsets empty() {
        return new RecoveryOffsets(Map.of());
    }

    /**
     * {@link #format()}이 만든 문자열을 읽는다. 빈 문자열은 빈 묶음이다.
     *
     * @throws IllegalArgumentException 형식이 틀렸다
     */
    public static RecoveryOffsets parse(String value) {
        if (value == null || value.isEmpty()) {
            return empty();
        }
        Map<Integer, Long> offsets = new TreeMap<>();
        for (String part : value.split(",")) {
            String[] pair = part.split(":");
            if (pair.length != 2) {
                throw new IllegalArgumentException("offset 경계 형식이 아니다 value=" + value);
            }
            try {
                if (offsets.put(Integer.parseInt(pair[0]), Long.parseLong(pair[1])) != null) {
                    throw new IllegalArgumentException("같은 partition이 두 번 있다 value=" + value);
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("offset 경계 형식이 아니다 value=" + value, e);
            }
        }
        return new RecoveryOffsets(offsets);
    }

    public String format() {
        return new TreeMap<>(offsets).entrySet().stream()
                .map(entry -> entry.getKey() + ":" + entry.getValue())
                .collect(Collectors.joining(","));
    }

    /**
     * 이 경계까지 {@code committed}(소비 그룹이 커밋한 다음 읽을 offset)가 아직 닿지 않은 메시지 수. 커밋이 없는 partition은 0부터 센다.
     * 경계보다 앞서 커밋한 partition은 0이다(경계 뒤의 메시지는 세지 않는다).
     */
    public long lagFrom(RecoveryOffsets committed) {
        long lag = 0;
        for (Map.Entry<Integer, Long> boundary : offsets.entrySet()) {
            long consumed = committed.offsets().getOrDefault(boundary.getKey(), 0L);
            lag += Math.max(0, boundary.getValue() - consumed);
        }
        return lag;
    }
}
