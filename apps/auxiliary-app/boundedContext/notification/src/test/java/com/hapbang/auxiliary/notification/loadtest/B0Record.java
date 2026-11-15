package com.hapbang.auxiliary.notification.loadtest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.apache.kafka.clients.producer.internals.BuiltInPartitioner;

/**
 * B0 레코드 한 건. {@link #line()}은 {@code kafka-console-producer.sh}의 {@code parse.headers}·{@code parse.key} 형식이다:
 * {@code __TypeId__:<클래스>} TAB {@code key} TAB {@code 본문 JSON}.
 *
 * @param event 직렬화한 원래 레코드(계약 왕복 시험용). LEGACY·깨진 본문이면 null
 */
public record B0Record(String topic, String key, String typeId, String value, B0Category category, B0EventType type,
                       Object event) {

    public B0Record {
        if (value.indexOf('\t') >= 0 || value.indexOf('\n') >= 0 || key.indexOf('\t') >= 0) {
            throw new IllegalArgumentException("탭·줄바꿈이 든 key·본문은 콘솔 producer 한 줄이 될 수 없다: " + key);
        }
    }

    public String line() {
        return "__TypeId__:" + typeId + "\t" + key + "\t" + value;
    }

    /** 발행 앱과 같은 kafka-clients의 내장 파티셔너(key가 있으면 toPositive(murmur2) % 파티션 수) */
    public int partition() {
        return BuiltInPartitioner.partitionForKey(key.getBytes(StandardCharsets.UTF_8), B0Topics.PARTITIONS);
    }

    /** key·타입·본문의 SHA-256 앞 16자. 투입 검증(V0)에서 실제 레코드와 대조한다 */
    public String digest() {
        return sha256(key + '\0' + typeId + '\0' + value).substring(0, 16);
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
