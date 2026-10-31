package com.hapbang.chat.in.event;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;

import com.hapbang.chat.in.event.ChatMemberEventTopicVerifier.Result;
import com.hapbang.chat.in.event.kafka.ChatKafkaConfig;
import com.hapbang.chat.in.event.kafka.ChatKafkaConstants;
import com.hapbang.chat.in.event.kafka.ChatKafkaConsumerSettings;
import com.hapbang.chat.out.ChatUserRepository;
import com.hapbang.shared.member.event.UserJoinedEvent;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.KafkaTestcontainersConfig;

/**
 * chat-app이 만드는 회원 이벤트 DLT 토픽의 실패 처리를 실제 Kafka로 검증한다.
 * <p>
 * 브로커가 1대인데 DLT 복제 수를 3으로 설정해 KafkaAdmin의 토픽 생성을 실패시킨다. 앱 시작은 막지 않고(fail-safe),
 * 회원 이벤트 소비는 계속되며, 시작 뒤 확인({@link ChatMemberEventTopicVerifier})이 DLT가 없다는 것을 ERROR 로그로 알린다.
 * 파티션 수 비교는 같은 컨텍스트에서 이 클래스 전용 토픽을 만들어 확인한다.
 */
@IntegrationTest
// 이 클래스만 쓰는 토픽·그룹 설정의 컨텍스트라 끝나면 닫아 Kafka 소비자를 남기지 않는다
@DirtiesContext
@Import({KafkaTestcontainersConfig.class, ChatMemberEventTopicTest.Topics.class})
@ExtendWith(OutputCaptureExtension.class)
class ChatMemberEventTopicTest {

    private static final String SUFFIX = UUID.randomUUID().toString();
    private static final String TOPIC = "member.events.v1.topic-test-" + SUFFIX;
    private static final String GROUP = "chat-app-topic-test-" + SUFFIX;
    private static final String DLT = TOPIC + "." + GROUP + ".DLT";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(ChatKafkaConstants.MEMBER_EVENTS_ENABLED, () -> "true");
    }

    /** 이 클래스 전용 토픽·그룹·DLT. 브로커 1대에 DLT 복제 3이라 DLT 생성이 실패한다 */
    @TestBean(name = ChatKafkaConfig.MEMBER_EVENT_SETTINGS)
    private ChatKafkaConsumerSettings memberEventSettings;

    static ChatKafkaConsumerSettings memberEventSettings() {
        return new ChatKafkaConsumerSettings(TOPIC, GROUP, DLT, ChatKafkaConstants.MAX_RETRIES,
                ChatKafkaConstants.RETRY_INITIAL_INTERVAL, ChatKafkaConstants.RETRY_MAX_INTERVAL,
                ChatKafkaConstants.DEAD_LETTER_SEND_TIMEOUT, ChatKafkaConstants.DEAD_LETTER_PARTITIONS, (short) 3);
    }

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Autowired
    private ChatMemberEventTopicVerifier topicVerifier;

    @Autowired
    private ChatUserRepository chatUserRepository;

    @Test
    void DLT를_만들지_못해도_앱은_뜨고_회원_이벤트를_소비하며_DLT가_없다고_ERROR로_알린다(CapturedOutput output) {
        assertThatThrownBy(() -> kafkaAdmin.describeTopics(DLT)).isInstanceOf(KafkaException.class);

        assertThat(topicVerifier.verify()).isEqualTo(Result.DEAD_LETTER_UNAVAILABLE);
        assertThat(output).contains("회원 이벤트 DLT " + DLT + "를 확인하지 못했습니다");

        long id = 300_001L;
        send(id, "{\"user\":{\"id\":" + id + ",\"nickname\":\"가입\",\"role\":\"INFLUENCER\",\"status\":\"ACTIVE\","
                + "\"version\":0}}");
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(chatUserRepository.findById(id)).isPresent());
    }

    @Test
    void DLT와_원본의_파티션_수가_다르면_경고하고_같으면_정상이다(CapturedOutput output) {
        String source = "verifier-source-" + UUID.randomUUID();
        kafkaAdmin.createOrModifyTopics(TopicBuilder.name(source).partitions(2).replicas(1).build(),
                TopicBuilder.name(source + ".same.DLT").partitions(2).replicas(1).build(),
                TopicBuilder.name(source + ".other.DLT").partitions(1).replicas(1).build());

        assertThat(verifier(source, "other").verify()).isEqualTo(Result.PARTITION_MISMATCH);
        assertThat(output).contains("파티션 수(1)가 원본 " + source + "(2)와 다릅니다");
        assertThat(verifier(source, "same").verify()).isEqualTo(Result.OK);
    }

    @Test
    void 원본_토픽이_아직_없으면_비교하지_못했다고_알린다() {
        String source = "verifier-missing-" + UUID.randomUUID();
        kafkaAdmin.createOrModifyTopics(TopicBuilder.name(source + ".g.DLT").partitions(1).replicas(1).build());

        assertThat(verifier(source, "g").verify()).isEqualTo(Result.SOURCE_UNAVAILABLE);
    }

    /** DLT 이름은 규칙대로 {@code <토픽>.<그룹>.DLT}다 */
    private ChatMemberEventTopicVerifier verifier(String topic, String groupId) {
        return new ChatMemberEventTopicVerifier(kafkaAdmin, new ChatKafkaConsumerSettings(topic, groupId,
                topic + "." + groupId + ".DLT", ChatKafkaConstants.MAX_RETRIES, ChatKafkaConstants.RETRY_INITIAL_INTERVAL,
                ChatKafkaConstants.RETRY_MAX_INTERVAL, ChatKafkaConstants.DEAD_LETTER_SEND_TIMEOUT,
                ChatKafkaConstants.DEAD_LETTER_PARTITIONS, ChatKafkaConstants.DEAD_LETTER_REPLICAS));
    }

    private void send(long key, String json) {
        Properties config = new Properties();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(config, new StringSerializer(),
                new ByteArraySerializer())) {
            ProducerRecord<String, byte[]> record = new ProducerRecord<>(TOPIC, String.valueOf(key),
                    json.getBytes(StandardCharsets.UTF_8));
            record.headers().add("__TypeId__", UserJoinedEvent.class.getName().getBytes(StandardCharsets.UTF_8));
            producer.send(record).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("회원 이벤트를 보내지 못했습니다.", e);
        }
    }

    /** 이 클래스 전용 원본 토픽 */
    @TestConfiguration(proxyBeanMethods = false)
    static class Topics {

        @Bean
        NewTopic chatMemberEventTopicTestSource() {
            return TopicBuilder.name(TOPIC).partitions(1).replicas(1).build();
        }
    }
}
