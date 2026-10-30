package com.hapbang.chat.in;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import com.hapbang.chat.app.ChatJobStream;
import com.hapbang.chat.app.ChatMessageUseCase;
import com.hapbang.chat.app.ChatOutboxRelay;
import com.hapbang.chat.app.ChatRoomUseCase;
import com.hapbang.chat.app.ChatUserUseCase;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.chat.in.stream.ChatDeliveryPipelineRunner;
import com.hapbang.chat.in.support.ChatE2ETest;
import com.hapbang.chat.in.support.ChatPipelineIsolationListener;
import com.hapbang.chat.out.redis.ChatRedisKeys;

/**
 * 한 E2E 메서드가 Stream에 적재만 하고 소비 전에 끝나도, 그 작업이 다음 메서드로 새지 않는지 검증한다
 * ({@link ChatPipelineIsolationListener}). 새면 다음 메서드의 worker가 원본이 지워진 작업을 읽고 새 DB에 DEAD 체크포인트를 남긴다.
 * <p>
 * 첫 메서드는 worker를 멈춘 채 relay만 직접 한 번 돌려 "XADD 뒤 소비 전"을 만들고 끝난다. 두 번째 메서드는 Stream이 비어 있고,
 * 파이프라인이 다시 돌며, 앞 작업의 DEAD가 생기지 않고, 새 작업은 정상 발행되는지 본다.
 */
@ChatE2ETest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChatPipelineIsolationE2ETest {

    @Autowired
    private ChatDeliveryPipelineRunner runner;

    @Autowired
    private ChatOutboxRelay relay;

    @Autowired
    private ChatRoomUseCase chatRoomUseCase;

    @Autowired
    private ChatMessageUseCase chatMessageUseCase;

    @Autowired
    private ChatUserUseCase chatUserUseCase;

    @Autowired
    private ChatRedisKeys keys;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        ChatUserSeeder.seedDefaultUsers(chatUserUseCase);
    }

    @Test
    @Order(1)
    void 적재만_하고_소비하지_않은_작업을_남긴_채_끝난다() {
        assertThat(runner.isRunning()).isTrue();
        Long chatRoomId = chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID).chatRoomId();
        runner.stop();

        chatMessageUseCase.send(chatRoomId, HOST_ID, "소비되지 않을 메시지", null);
        assertThat(relay.relayBatch(ChatJobStream.DELIVERY)).isEqualTo(1);

        assertThat(redis.opsForStream().size(keys.deliveryStream())).isEqualTo(1);
        assertThat(checkpoints("PENDING")).isEqualTo(1);
    }

    @Test
    @Order(2)
    void 다음_메서드는_앞_메서드의_작업_없이_깨끗한_파이프라인에서_시작한다() {
        assertThat(runner.isRunning()).isTrue();
        assertThat(redis.opsForStream().size(keys.deliveryStream())).isZero();
        assertThat(redis.opsForStream().groups(keys.deliveryStream())).isNotEmpty();
        // 앞 메서드의 작업을 읽어 새 DB에 DEAD를 남기지 않는다
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3))
                .until(() -> checkpoints(null) == 0);

        Long chatRoomId = chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID).chatRoomId();
        chatMessageUseCase.send(chatRoomId, HOST_ID, "새 메서드의 메시지", null);

        await().atMost(Duration.ofSeconds(10)).until(() -> checkpoints("PUBLISHED") == 1);
        assertThat(checkpoints("DEAD")).isZero();
    }

    /** 전달 그룹의 발행 체크포인트 수. status가 null이면 전체 */
    private long checkpoints(String status) {
        String sql = "select count(*) from chats.chat_publish_checkpoint where consumer_group = ?"
                + (status == null ? "" : " and status = ?");
        Object[] args = status == null
                ? new Object[] {ChatJobStream.DELIVERY.consumerGroup()}
                : new Object[] {ChatJobStream.DELIVERY.consumerGroup(), status};
        Long count = jdbcTemplate.queryForObject(sql, Long.class, args);
        return count == null ? 0 : count;
    }
}
