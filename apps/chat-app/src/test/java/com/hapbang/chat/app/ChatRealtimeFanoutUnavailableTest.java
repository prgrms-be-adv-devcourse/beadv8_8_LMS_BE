package com.hapbang.chat.app;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.hapbang.chat.app.dto.MessageFanout;
import com.hapbang.chat.domain.ChatPublishCheckpoint;
import com.hapbang.chat.domain.ChatPublishStatus;
import com.hapbang.chat.out.redis.ChatRedisKeys;
import com.hapbang.chat.out.redis.RedisRealtimeChatFanout;

import tools.jackson.databind.json.JsonMapper;

/**
 * 실시간 발행({@link RedisRealtimeChatFanout})이 Redis에 닿지 못하면, 전달 consumer가 작업 실패로 세지 않고
 * 파이프라인을 멈췄다가(PAUSED) 연결이 돌아오면 한 번 발행하는지 검증한다.
 * <p>
 * 테스트의 메모리 발행 빈을 감싸, 발행을 아무도 듣지 않는 포트를 가리키는 실제 {@link RedisRealtimeChatFanout}으로 넘긴다. 그래서
 * Lettuce의 실제 연결 실패가 발행 구현의 분류({@code RedisFailures})를 거쳐 consumer까지 간다. Stream·체크포인트는 공용 Redis를 쓴다.
 */
@DirtiesContext
class ChatRealtimeFanoutUnavailableTest extends ChatDeliveryTestSupport {

    private static final Duration WAIT = Duration.ofSeconds(10);

    @DynamicPropertySource
    static void redisNamespace(DynamicPropertyRegistry registry) {
        uniqueNamespace(registry);
    }

    @MockitoSpyBean
    private InMemoryRealtimeChatFanout spiedFanout;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private ChatRedisKeys chatRedisKeys;

    private LettuceConnectionFactory unreachableFactory;
    private RedisRealtimeChatFanout unreachableFanout;

    @BeforeEach
    void setUpUnreachableRedis() throws Exception {
        int deadPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            deadPort = socket.getLocalPort();
        }
        unreachableFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", deadPort),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(500)).build());
        unreachableFactory.afterPropertiesSet();
        unreachableFactory.start();
        unreachableFanout = new RedisRealtimeChatFanout(new StringRedisTemplate(unreachableFactory), jsonMapper,
                chatRedisKeys);
    }

    @AfterEach
    void tearDownUnreachableRedis() {
        unreachableFactory.destroy();
    }

    @Test
    void 실시간_발행이_Redis에_닿지_못하면_시도로_세지_않고_멈췄다가_연결이_돌아오면_한_번_발행한다() {
        UUID deliveryId = send("발행 연결 장애");
        relayAll(ChatJobStream.DELIVERY);
        doAnswer(invocation -> unreachableFanout.publishMessage(invocation.getArgument(0)))
                .when(spiedFanout).publishMessage(any());

        pollAll(ChatJobStream.DELIVERY, WORKER);

        ChatPublishCheckpoint paused = checkpoint(deliveryId);
        assertThat(paused.getStatus()).isEqualTo(ChatPublishStatus.PENDING);
        assertThat(paused.getAttemptCount()).as("연결 장애는 발행 시도로 세지 않는다").isZero();
        assertThat(paused.getTotalAttemptCount()).isZero();
        assertThat(paused.getLastError()).contains("Redis 연결 불가");
        assertThat(spiedFanout.messages()).isEmpty();

        // 발행 경로가 다시 Redis에 닿는다: 재확인(ping) 뒤 회수 루프가 이어서 한 번 발행한다
        doCallRealMethod().when(spiedFanout).publishMessage(any());
        await().atMost(WAIT).until(() -> {
            processor.reclaim(ChatJobStream.DELIVERY, RECLAIMER, 10);
            processor.pollNew(ChatJobStream.DELIVERY, WORKER);
            return checkpoint(deliveryId).getStatus() == ChatPublishStatus.PUBLISHED;
        });

        assertThat(checkpoint(deliveryId).getAttemptCount()).isZero();
        assertThat(spiedFanout.messages()).extracting(MessageFanout::deliveryId).containsExactly(deliveryId);
        assertThat(pendingCount(ChatJobStream.DELIVERY)).isZero();
    }
}
