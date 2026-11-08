package com.hapbang.coupon.in.event;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.stereotype.Component;

import com.hapbang.coupon.app.CouponDltPayload;
import com.hapbang.coupon.app.CouponDltRecordUseCase;
import com.hapbang.coupon.domain.CouponDltLog;
import com.hapbang.shared.coupon.event.CouponIssueRequested;

import lombok.RequiredArgsConstructor;

/**
 * 재시도를 다 쓴(또는 재시도하지 않는) 발급 요청 레코드에서 격리 기록에 남길 값(위치·쿠폰 ID·요청 ID·원문·실패 원인)을 꺼내
 * {@link CouponDltRecordUseCase}에 넘긴다. Kafka 오류 처리기가 DLT로 보내기 전에 부른다.
 * <ul>
 *     <li>읽은 메시지: 원문은 이벤트 JSON, 쿠폰 ID·요청 ID는 이벤트 값.</li>
 *     <li>역직렬화에 실패한 메시지: 원문은 받은 바이트(UTF-8), 쿠폰 ID는 key(쿠폰 ID)가 숫자면 그 값, 요청 ID는 없다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class CouponIssueDeadLetterRecorder {

    private final CouponDltRecordUseCase couponDltRecordUseCase;

    public void record(ConsumerRecord<?, ?> record, Exception exception) {
        String key = record.key() == null ? null : record.key().toString();
        CouponDltLog.Source source = new CouponDltLog.Source(record.topic(), record.partition(), record.offset(), key);
        String cause = causeOf(exception);
        if (record.value() instanceof CouponIssueRequested event) {
            couponDltRecordUseCase.record(source, event.couponId(), event.issueRequestId(), CouponDltPayload.write(event),
                    cause);
            return;
        }
        couponDltRecordUseCase.record(source, parseCouponId(key), null, rawPayload(record, exception), cause);
    }

    private static String rawPayload(ConsumerRecord<?, ?> record, Exception exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof DeserializationException deserialization && deserialization.getData() != null) {
                return new String(deserialization.getData(), StandardCharsets.UTF_8);
            }
        }
        return record.value() == null ? "" : record.value().toString();
    }

    /** 가장 안쪽 원인의 타입과 메시지(리스너 실행 예외로 감싼 껍데기를 벗긴다) */
    private static String causeOf(Exception exception) {
        Throwable root = exception;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    private static Long parseCouponId(String key) {
        if (key == null) {
            return null;
        }
        try {
            return Long.parseLong(key);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
