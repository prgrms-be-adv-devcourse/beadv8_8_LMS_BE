package com.hapbang.auxiliary.notification.in.event.kafka;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;

/**
 * 받은 Kafka 레코드의 이벤트 종류와 대상을 MDC에 넣어, 그 레코드를 처리하며 찍는 로그(리스너, 재시도, DLT 전송)에 붙인다
 * (docs/dev/logging.md "MDC 키"). common/kafka의 같은 이름 클래스를 복사했다: 업무 모듈은 {@code :kafka} 프로젝트를 의존할 수
 * 없다(루트 verifyModuleStructure, auxiliary-app README).
 * <ul>
 *     <li>{@value #EVENT_TYPE}: 헤더 {@code eventType}(발행 계약, kafka.md "발행"). 헤더가 없으면 넣지 않는다.</li>
 *     <li>{@value #EVENT_KEY}: 레코드 key(회원 이벤트는 회원 ID). key가 없으면 넣지 않는다.</li>
 * </ul>
 * 리스너 스레드는 다음 레코드에 다시 쓰이므로 레코드 처리가 끝나면 지운다.
 */
public class EventLogContextRecordInterceptor<K, V> implements RecordInterceptor<K, V> {

    /** 이벤트 종류를 로그에 붙이는 MDC 키 */
    public static final String EVENT_TYPE = "eventType";
    /** 이벤트 대상(레코드 key)을 로그에 붙이는 MDC 키 */
    public static final String EVENT_KEY = "eventKey";
    /** 발행하는 앱이 싣는 이벤트 종류 헤더 */
    static final String EVENT_TYPE_HEADER = "eventType";

    @Override
    public ConsumerRecord<K, V> intercept(ConsumerRecord<K, V> record, Consumer<K, V> consumer) {
        clear();
        Header eventType = record.headers().lastHeader(EVENT_TYPE_HEADER);
        if (eventType != null && eventType.value() != null) {
            MDC.put(EVENT_TYPE, new String(eventType.value(), StandardCharsets.UTF_8));
        }
        Object key = record.key();
        if (key instanceof byte[] bytes) {
            MDC.put(EVENT_KEY, new String(bytes, StandardCharsets.UTF_8));
        } else if (key != null) {
            MDC.put(EVENT_KEY, key.toString());
        }
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<K, V> record, Consumer<K, V> consumer) {
        clear();
    }

    @Override
    public void clearThreadState(Consumer<?, ?> consumer) {
        clear();
    }

    private static void clear() {
        MDC.remove(EVENT_TYPE);
        MDC.remove(EVENT_KEY);
    }
}
