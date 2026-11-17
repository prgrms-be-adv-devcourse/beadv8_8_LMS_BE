package com.hapbang.coupon.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.listener.ListenerExecutionFailedException;

import com.hapbang.coupon.app.CouponDltPayload;
import com.hapbang.coupon.domain.CouponDltKind;
import com.hapbang.coupon.domain.CouponDltLog;
import com.hapbang.coupon.domain.CouponDltStatus;
import com.hapbang.coupon.support.CouponDltLogTestRepository;
import com.hapbang.shared.coupon.event.CouponEventTopic;
import com.hapbang.shared.coupon.event.CouponIssueRequested;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.RedisTestcontainersConfig;

/**
 * 재시도를 다 쓴 발급 요청 레코드의 격리 기록이 레코드의 위치(토픽·partition·offset·key)와 이벤트 값(쿠폰 ID·요청 ID·원문)으로 실제 DB에
 * 남는지 본다. 실제 Kafka 전달·DLT 전송은 {@code CouponIssueDeliveryE2ETest}가 본다.
 */
@IntegrationTest
@Import(RedisTestcontainersConfig.class)
class CouponIssueDeadLetterRecorderTest {

    @Autowired
    private CouponIssueDeadLetterRecorder recorder;

    @Autowired
    private CouponDltLogTestRepository couponDltLogTestRepository;

    @Test
    void 읽은_발급_요청은_메시지_위치와_이벤트_값으로_PENDING_격리_기록이_된다() {
        long couponId = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE / 2, Long.MAX_VALUE);
        long offset = ThreadLocalRandom.current().nextLong(1_000_000, Long.MAX_VALUE / 2);
        Instant acceptedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        CouponIssueRequested event = new CouponIssueRequested("req-" + UUID.randomUUID(), couponId, 7, 1, acceptedAt,
                acceptedAt.plusSeconds(86_400));
        ConsumerRecord<String, Object> record = new ConsumerRecord<>(CouponEventTopic.NAME, 1, offset,
                String.valueOf(couponId), event);

        recorder.record(record, new ListenerExecutionFailedException("리스너 실패", new IllegalStateException("처리 실패")));

        CouponDltLog saved = couponDltLogTestRepository.findAll().stream()
                .filter(log -> CouponEventTopic.NAME.equals(log.getSourceTopic()) && log.getSourceOffset() == offset)
                .findFirst().orElseThrow();
        assertThat(saved.getKind()).isEqualTo(CouponDltKind.ISSUE);
        assertThat(saved.getStatus()).isEqualTo(CouponDltStatus.PENDING);
        assertThat(saved.getSourcePartition()).isEqualTo(1);
        assertThat(saved.getMessageKey()).isEqualTo(String.valueOf(couponId));
        assertThat(saved.getCouponId()).isEqualTo(couponId);
        assertThat(saved.getIssueRequestId()).isEqualTo(event.issueRequestId());
        assertThat(saved.getPayload()).isEqualTo(CouponDltPayload.write(event));
        assertThat(saved.getFailureCause()).isEqualTo("IllegalStateException: 처리 실패");
    }
}
