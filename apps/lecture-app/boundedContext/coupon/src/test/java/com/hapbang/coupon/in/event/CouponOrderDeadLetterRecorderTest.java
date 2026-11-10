package com.hapbang.coupon.in.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.support.serializer.DeserializationException;

import com.hapbang.coupon.app.CouponDltPayload;
import com.hapbang.coupon.domain.CouponDltKind;
import com.hapbang.coupon.domain.CouponDltLog;
import com.hapbang.coupon.out.CouponDltLogRepository;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.RedisTestcontainersConfig;

/**
 * 역직렬화에 실패한 주문 이벤트의 받은 바이트에 NUL(0x00)이 있어도 격리 기록이 저장되는지 실제 DB(text 컬럼)로 본다. 저장이 실패하면 오류
 * 처리기가 DLT로 보내지 못하고 같은 메시지를 계속 다시 시도해 그 partition 소비가 멈춘다. 원문은 {@code base64:} + Base64로 남고 재처리에서는
 * 주문 이벤트로 읽히지 않는다(영구 거절로 닫는다). 발급 쪽 recorder는 같은 helper를 쓰며 실제 Kafka로는 {@code CouponIssueDeliveryE2eTest}가 본다.
 */
@IntegrationTest
@Import(RedisTestcontainersConfig.class)
class CouponOrderDeadLetterRecorderTest {

    @Autowired
    private CouponOrderDeadLetterRecorder recorder;

    @Autowired
    private CouponDltLogRepository couponDltLogRepository;

    @Test
    void NUL이_든_깨진_주문_이벤트는_Base64_원문으로_격리_기록이_저장된다() {
        byte[] data = "{\"eventId\":\"order-item-paid:1\u0000\"".getBytes(StandardCharsets.UTF_8);
        ConsumerRecord<String, Object> record = new ConsumerRecord<>("lecture.events.v1", 2, 31L, "order-item:1", null);
        DeserializationException broken = new DeserializationException("역직렬화 실패", data, false, null);

        recorder.record(record, new ListenerExecutionFailedException("리스너 실패", broken));

        CouponDltLog saved = couponDltLogRepository.findAll().stream()
                .filter(log -> log.getSourceOffset() == 31L).findFirst().orElseThrow();
        assertThat(saved.getKind()).isEqualTo(CouponDltKind.ORDER);
        assertThat(saved.getPayload()).isEqualTo(CouponDltPayload.BASE64_PREFIX + Base64.getEncoder().encodeToString(data));
        assertThat(CouponDltPayload.readOrderEvent(saved.getPayload())).isEmpty();
    }
}
