package com.hapbang.coupon.in.event;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.stereotype.Component;

import com.hapbang.coupon.app.CouponDltPayload;
import com.hapbang.coupon.app.CouponDltRecordUseCase;
import com.hapbang.coupon.domain.CouponDltLog;
import com.hapbang.shared.lecture.event.OrderItemCancelCompletedEvent;
import com.hapbang.shared.lecture.event.OrderItemPaidEvent;

import lombok.RequiredArgsConstructor;

/**
 * 재시도를 다 쓴(또는 재시도하지 않는) 주문 결제·취소 완료 레코드에서 격리 기록에 남길 값(위치·key·원문·실패 원인)을 꺼내
 * {@link CouponDltRecordUseCase#recordOrderEvent}에 넘긴다. Kafka 오류 처리기가 DLT로 보내기 전에 부른다.
 * <ul>
 *     <li>읽은 메시지: 원문은 이벤트 JSON. 관리자 재처리가 이 원문을 다시 읽는다.</li>
 *     <li>역직렬화에 실패한 메시지: 원문은 받은 바이트({@link CouponDltPayload#rawText}. UTF-8 문자열, NUL·잘못된 UTF-8이면
 *     {@code base64:} + Base64). 재처리할 수 없고 영구 거절로 닫는다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class CouponOrderDeadLetterRecorder {

    private final CouponDltRecordUseCase couponDltRecordUseCase;

    public void record(ConsumerRecord<?, ?> record, Exception exception) {
        String key = record.key() == null ? null : record.key().toString();
        CouponDltLog.Source source = new CouponDltLog.Source(record.topic(), record.partition(), record.offset(), key);
        Object value = record.value();
        String payload = value instanceof OrderItemPaidEvent || value instanceof OrderItemCancelCompletedEvent
                ? CouponDltPayload.writeOrderEvent(value)
                : rawPayload(record, exception);
        couponDltRecordUseCase.recordOrderEvent(source, payload, causeOf(exception));
    }

    private static String rawPayload(ConsumerRecord<?, ?> record, Exception exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof DeserializationException deserialization && deserialization.getData() != null) {
                return CouponDltPayload.rawText(deserialization.getData());
            }
        }
        return record.value() == null ? ""
                : CouponDltPayload.rawText(record.value().toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 가장 안쪽 원인의 타입과 메시지(리스너 실행 예외로 감싼 껍데기를 벗긴다) */
    private static String causeOf(Exception exception) {
        Throwable root = exception;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
