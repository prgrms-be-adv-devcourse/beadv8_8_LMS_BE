package com.hapbang.chat.app;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * 전달 파이프라인(outbox relay → Stream 소비·회수·재구성·정리) 설정 ({@code hapbang.chat.delivery.*}).
 * <p>
 * 모든 수치는 미확정 초기값(Q12)이다. 인기 방·지속 과부하·장애 시험으로 확정하기 전에는 출시 준비 완료로 보지 않는다.
 * <p>
 * 기본값은 이 클래스의 {@code @DefaultValue}가 원본이다(application.yaml에 두지 않는다). 환경별로 바꿀 때만 yaml·환경 변수로
 * 덮는다(예: {@code HAPBANG_CHAT_DELIVERY_RELAY_BATCH_SIZE=100}).
 *
 * @param pipeline    파이프라인 실행 여부
 * @param instanceId  consumer 이름의 앞부분(실행 인스턴스 식별자). 비우면 {@code HOSTNAME} 환경 변수, 없으면 {@code chat-app}
 * @param relay       DB outbox → Stream 적재
 * @param consumer    Stream 새 작업 소비와 Pub/Sub 발행 재시도
 * @param reclaim     미확인(PEL) 작업 회수
 * @param reconstruct Redis 유실 뒤 DB 미완료 작업 재적재
 * @param trim        Stream 본문 정리
 * @param pause       Redis 연결 불가·자기 구독 단절 동안의 일시 중지와 재확인
 * @param shutdownAwait 멈출 때 주기 작업·worker가 끝나기를 기다리는 상한
 */
@ConfigurationProperties("hapbang.chat.delivery")
public record ChatDeliveryProperties(
        @DefaultValue Pipeline pipeline,
        String instanceId,
        @DefaultValue Relay relay,
        @DefaultValue Consumer consumer,
        @DefaultValue Reclaim reclaim,
        @DefaultValue Reconstruct reconstruct,
        @DefaultValue Trim trim,
        @DefaultValue Pause pause,
        @DefaultValue("10s") Duration shutdownAwait
) {

    public static final String PIPELINE_ENABLED = "hapbang.chat.delivery.pipeline.enabled";

    /** 실제 consumer 이름에 쓸 인스턴스 식별자 */
    public String resolvedInstanceId() {
        if (StringUtils.hasText(instanceId)) {
            return instanceId;
        }
        String hostname = System.getenv("HOSTNAME");
        return StringUtils.hasText(hostname) ? hostname : "chat-app";
    }

    /**
     * @param enabled relay·worker·회수·재구성·정리를 실행한다. 운영 {@link RealtimeChatFanout} 구현과 합친 통합에서 기본을
     *                켰다. 속성이 없으면 켠 것으로 본다({@code ConditionalOnChatDeliveryPipeline}과 같다)
     */
    public record Pipeline(@DefaultValue("true") boolean enabled) {
    }

    /**
     * @param pollInterval 적재 대상을 찾는 주기
     * @param batchSize    한 번에 선점하는 outbox 행 수
     * @param lease        처리권 유지 시간. 넘기면 다른 relay가 회수한다
     * @param maxAttempts  적재 시도 상한. 넘기면 outbox를 DEAD로 격리한다
     */
    public record Relay(
            @DefaultValue("200ms") Duration pollInterval,
            @DefaultValue("50") int batchSize,
            @DefaultValue("30s") Duration lease,
            @DefaultValue("10") int maxAttempts,
            @DefaultValue Backoff backoff
    ) {
        public Relay {
            Assert.isTrue(batchSize > 0 && maxAttempts > 0, "relay batch-size·max-attempts는 1 이상이어야 합니다.");
        }
    }

    /**
     * @param deliveryWorkers 전달 Stream의 새 작업 worker 수
     * @param controlWorkers  제어 Stream의 새 작업 worker 수. 메시지 적체에 굶지 않도록 따로 둔다
     * @param readCount       한 번에 읽는 새 작업 수
     * @param block           새 작업이 없을 때 기다리는 시간
     * @param maxAttempts     연결된 Redis에서의 Pub/Sub 발행 실패 상한. 넘기면 체크포인트를 DEAD로 격리한 뒤 ACK한다.
     *                        구독자 0명(자기 구독 단절)·Redis 연결 불가는 인프라 장애라 세지 않는다
     */
    public record Consumer(
            @DefaultValue("2") int deliveryWorkers,
            @DefaultValue("1") int controlWorkers,
            @DefaultValue("20") int readCount,
            @DefaultValue("2s") Duration block,
            @DefaultValue("10") int maxAttempts,
            @DefaultValue Backoff backoff
    ) {
        public Consumer {
            Assert.isTrue(deliveryWorkers > 0 && controlWorkers > 0, "worker 수는 1 이상이어야 합니다.");
            Assert.isTrue(readCount > 0 && maxAttempts > 0, "consumer read-count·max-attempts는 1 이상이어야 합니다.");
            Assert.isTrue(!block.isNegative() && !block.isZero(), "consumer block은 0보다 커야 합니다(0은 무한 대기).");
        }
    }

    /**
     * @param interval       회수 주기
     * @param minIdle        회수할 최소 미확인 시간. 정상 처리 deadline보다 길게 둔다
     * @param count          한 번의 회수 명령으로 가져오는 수
     * @param deliveryBudget 한 주기에 회수·처리하는 전달 작업 상한
     * @param controlBudget  한 주기에 회수·처리하는 제어 작업 상한
     */
    public record Reclaim(
            @DefaultValue("5s") Duration interval,
            @DefaultValue("30s") Duration minIdle,
            @DefaultValue("20") int count,
            @DefaultValue("100") int deliveryBudget,
            @DefaultValue("50") int controlBudget
    ) {
    }

    /**
     * @param interval          DB 대조 주기
     * @param enqueuedThreshold 적재 뒤 이 시간이 지나도 발행하지 못한 작업만 대조한다(정상 backlog와 겹치지 않게)
     * @param cooldown          같은 작업을 다시 적재하기까지의 최소 간격
     * @param maxRequeues       재적재 상한. 넘긴 작업은 경보만 남기고 운영 재처리를 기다린다
     * @param batchSize         한 번에 대조하는 작업 수
     */
    public record Reconstruct(
            @DefaultValue("60s") Duration interval,
            @DefaultValue("5m") Duration enqueuedThreshold,
            @DefaultValue("5m") Duration cooldown,
            @DefaultValue("5") int maxRequeues,
            @DefaultValue("100") int batchSize
    ) {
    }

    /**
     * @param interval  정리 주기
     * @param retention 확인(ACK)된 항목 본문을 남겨 두는 최소 시간
     */
    public record Trim(
            @DefaultValue("10m") Duration interval,
            @DefaultValue("24h") Duration retention
    ) {
    }

    /**
     * Redis 연결 불가·자기 구독 단절 동안 작업을 멈추고 재확인하는 간격. 재확인이 실패할 때마다 두 배로 늘려 {@code probeMax}에서
     * 멈춘다. 이 동안의 실패는 작업의 시도 횟수에 넣지 않는다.
     *
     * @param probeInitial 첫 재확인까지의 간격
     * @param probeMax     재확인 간격 상한
     */
    public record Pause(
            @DefaultValue("1s") Duration probeInitial,
            @DefaultValue("30s") Duration probeMax
    ) {
        public Pause {
            Assert.isTrue(!probeInitial.isNegative() && !probeInitial.isZero(), "pause probe-initial은 0보다 커야 합니다.");
        }
    }

    /**
     * 재시도 간격. {@code initial × 2^(시도-1)}을 {@code max}로 자르고 ±{@code jitter} 비율로 흔든다.
     */
    public record Backoff(
            @DefaultValue("1s") Duration initial,
            @DefaultValue("60s") Duration max,
            @DefaultValue("0.2") double jitter
    ) {
        public Backoff {
            Assert.isTrue(jitter >= 0 && jitter < 1, "backoff jitter는 0 이상 1 미만이어야 합니다.");
        }
    }
}
