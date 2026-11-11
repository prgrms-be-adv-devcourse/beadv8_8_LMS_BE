package com.hapbang.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.hapbang.coupon.app.CouponDltPayload;
import com.hapbang.coupon.app.CouponIssueConsumerUseCase;
import com.hapbang.coupon.app.CouponRepublishUseCase;
import com.hapbang.coupon.app.port.CouponIssueEventPublisher;
import com.hapbang.coupon.app.port.CouponIssueEventPublisher.PublishResult;
import com.hapbang.coupon.app.port.LectureCatalog;
import com.hapbang.coupon.config.CouponIssueConsumerKafkaConfig;
import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponDltLog;
import com.hapbang.coupon.domain.CouponDltStatus;
import com.hapbang.coupon.domain.CouponIssueRequest;
import com.hapbang.coupon.domain.CouponIssueResult;
import com.hapbang.coupon.domain.IssueRequestRecord;
import com.hapbang.coupon.out.CouponDltLogRepository;
import com.hapbang.coupon.out.CouponIssueRequestRepository;
import com.hapbang.coupon.out.CouponRepository;
import com.hapbang.coupon.out.MemberCouponRepository;
import com.hapbang.coupon.out.redis.CouponRedisKeys;
import com.hapbang.shared.coupon.event.CouponEventTopic;
import com.hapbang.shared.coupon.event.CouponIssueRequested;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.KafkaTestcontainersConfig;
import com.hapbang.testsupport.RedisTestcontainersConfig;

import com.jayway.jsonpath.JsonPath;

/**
 * 발급 접수 → Kafka → 발급 확정 소비자 → DB를 HTTP부터 PostgreSQL·Redis·Kafka까지 관통해 확인한다(I1·I3·I4). 분기별 규칙은 도메인·UseCase·
 * 리포지토리·Lua 테스트가 맡고, 여기서는 단계가 이어지는지와 전달 장애 뒤 복구만 본다.
 * <ol>
 *     <li>접수 직후 전송으로 DB ISSUED, 같은 메시지 재전달에도 발급 1건·확정 발급 수 +1 한 번. Kafka 전송이 실패한 접수는 ACCEPTED·재고 −1로
 *     남고, 재발행 배치가 다시 보내 DB ISSUED가 된다.</li>
 *     <li>소비 실패 → 제한 재시도 → 격리 기록·DLT → 관리자 영구 거절 → 재고 +1·회원 접수 기록 삭제. 다시 거절해도 재고는 그대로다.</li>
 *     <li>NUL이 든 깨진 메시지는 원문을 Base64로 남겨 격리되고, 같은 partition의 뒤 메시지가 처리된다.</li>
 * </ol>
 * 같은 소비 그룹의 다른 테스트 컨텍스트가 살아 있으면 partition을 나눠 가지므로 이 컨텍스트는 클래스가 끝나면 닫는다.
 * 보안·예산·강의 조회는 {@code CouponIssueE2eTest}와 같다(메서드 보안을 켜고, 다른 앱은 mock).
 */
@IntegrationTest
@AutoConfigureMockMvc
@DirtiesContext
@TestPropertySource(properties = {
        "spring.jpa.open-in-view=false",
        // 모듈 테스트 설정이 끈 Kafka 리스너·토픽 선언을 이 테스트만 켠다
        "spring.kafka.listener.auto-startup=true",
        "spring.kafka.admin.auto-create=true",
        // 재발행 대기를 줄여 전송 실패한 접수를 바로 재발행 대상으로 만든다
        "hapbang.coupon.republish-after=1ms",
        // 소비 실패의 재시도를 짧게 한다(운영 기본값은 3회·1초부터)
        "hapbang.coupon.issue-consumer.retry-attempts=1",
        "hapbang.coupon.issue-consumer.retry-interval=100ms",
        "hapbang.coupon.issue-consumer.retry-max-interval=100ms"
})
@Import({RedisTestcontainersConfig.class, KafkaTestcontainersConfig.class,
        CouponIssueDeliveryE2eTest.MethodSecurityConfig.class})
class CouponIssueDeliveryE2eTest {

    private static final long ADMIN_ID = 1L;
    private static final int QUANTITY = 10;
    private static final long DISCOUNT_HP = 1_000L;
    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private CouponIssueRequestRepository couponIssueRequestRepository;

    @Autowired
    private MemberCouponRepository memberCouponRepository;

    @Autowired
    private CouponDltLogRepository couponDltLogRepository;

    @Autowired
    private CouponRepublishUseCase couponRepublishUseCase;

    @MockitoSpyBean
    private CouponIssueEventPublisher couponIssueEventPublisher;

    @MockitoSpyBean
    private CouponIssueConsumerUseCase couponIssueConsumerUseCase;

    @Autowired
    private com.hapbang.testsupport.billing.RealBillingTestFixture billing;

    @MockitoBean
    private LectureCatalog lectureCatalog;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    private final List<Long> createdCouponIds = new ArrayList<>();
    /** 공유 Kafka 토픽에 앞선 실행의 같은 ID가 남아 있을 수 있어 요청 ID를 실행마다 다르게 만든다 */
    private final String run = UUID.randomUUID().toString().substring(0, 8);

    @AfterEach
    void cleanUpRedis() {
        for (long couponId : createdCouponIds) {
            redisTemplate.delete(List.of(CouponRedisKeys.stock(couponId), CouponRedisKeys.users(couponId),
                    CouponRedisKeys.requests(couponId), CouponRedisKeys.issueControl(couponId),
                    CouponRedisKeys.soldOut(couponId)));
        }
    }

    @Test
    void 접수가_Kafka를_거쳐_한_번만_발급되고_전송에_실패한_접수는_재발행으로_발급된다() throws Exception {
        long couponId = openPlatformCoupon();

        // 접수 직후 전송 → 소비자 → DB ISSUED, 상태 조회 ISSUED
        String requestA = "a-" + run;
        requestIssue(couponId, 100L, requestA).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.result").value("ACCEPTED"));
        awaitResult(couponId, requestA, CouponIssueResult.ISSUED);
        issueStatus(couponId, 100L, requestA).andExpect(jsonPath("$.data.status").value("ISSUED"));

        // 같은 메시지를 다시 보낸다. 같은 쿠폰(key)의 메시지는 순서대로 처리되므로, 뒤에 보낸 다른 회원의 접수가 확정되면 재전달도 처리된 것이다
        IssueRequestRecord record = IssueRequestRecord.parse(requestRecord(couponId, requestA));
        CouponIssueRequested again = new CouponIssueRequested(requestA, couponId, 100L, Coupon.INITIAL_EPOCH,
                record.acceptedAt(), record.expiresAt());
        assertThat(couponIssueEventPublisher.publish(again, Duration.ofSeconds(5)).acked()).isTrue();
        String requestB = "b-" + run;
        requestIssue(couponId, 200L, requestB).andExpect(status().isAccepted());
        awaitResult(couponId, requestB, CouponIssueResult.ISSUED);
        assertThat(memberCouponRepository.findByMemberIdAndCouponId(100L, couponId)).isPresent();
        assertThat(memberCouponRepository.findAll()).filteredOn(memberCoupon -> memberCoupon.getCouponId() == couponId)
                .hasSize(2);
        assertThat(issuedCount(couponId)).isEqualTo(2);

        // Kafka 전송이 실패해도 접수는 ACCEPTED이고 재고는 되돌리지 않는다. DB에는 아직 결과가 없다
        doReturn(PublishResult.FAILED).when(couponIssueEventPublisher).publish(any(), any());
        String requestC = "c-" + run;
        requestIssue(couponId, 300L, requestC).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.result").value("ACCEPTED"));
        assertThat(stock(couponId)).isEqualTo(QUANTITY - 3);
        assertThat(IssueRequestRecord.parse(requestRecord(couponId, requestC)).published()).isFalse();
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2))
                .until(() -> couponIssueRequestRepository.findByCouponIdAndIssueRequestId(couponId, requestC).isEmpty());
        issueStatus(couponId, 300L, requestC).andExpect(jsonPath("$.data.status").value("PENDING"));

        // 전송이 돌아온 뒤 재발행 배치가 같은 요청을 다시 보내 DB ISSUED가 된다
        doCallRealMethod().when(couponIssueEventPublisher).publish(any(), any());
        assertThat(couponRepublishUseCase.republishDue().acked()).isGreaterThanOrEqualTo(1);
        awaitResult(couponId, requestC, CouponIssueResult.ISSUED);
        assertThat(issuedCount(couponId)).isEqualTo(3);
        assertThat(stock(couponId)).isEqualTo(QUANTITY - 3);
    }

    @Test
    void 소비에_실패한_요청은_DLT로_격리되고_영구_거절하면_재고와_회원_기록이_한_번만_돌아온다() throws Exception {
        long couponId = openPlatformCoupon();
        String requestId = "fail-" + run;
        long memberId = 400L;
        doThrow(new IllegalStateException("주입한 소비 실패")).when(couponIssueConsumerUseCase)
                .consume(argThat(event -> event != null && requestId.equals(event.issueRequestId())));

        requestIssue(couponId, memberId, requestId).andExpect(status().isAccepted());
        assertThat(stock(couponId)).isEqualTo(QUANTITY - 1);

        // 제한 재시도 뒤 격리 기록(PENDING)과 그룹별 DLT 메시지가 남는다. 발급 결과는 없다
        CouponDltLog isolated = await().atMost(WAIT).until(() -> couponDltLogRepository
                .findFirstByCouponIdAndIssueRequestIdAndStatus(couponId, requestId, CouponDltStatus.PENDING)
                .orElse(null), log -> log != null);
        assertThat(isolated.getFailureCause()).contains("주입한 소비 실패");
        assertThat(deadLetterContains(String.valueOf(couponId), requestId)).isTrue();
        assertThat(couponIssueRequestRepository.findByCouponIdAndIssueRequestId(couponId, requestId)).isEmpty();
        mockMvc.perform(get("/api/v1/coupons/admin/dlt").param("status", "PENDING").with(login(ADMIN_ID, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[?(@.issueRequestId == '" + requestId + "')]").exists());
        // 관리자가 아니면 Facade 역할 검사가 막는다(운영은 /admin URL 권한이 먼저 막는다)
        mockMvc.perform(get("/api/v1/coupons/admin/dlt").with(login(memberId, "USER")))
                .andExpect(status().isForbidden());

        // 영구 거절: 발급 결과 REJECTED, 재고 +1, 회원 접수 기록 삭제
        reject(isolated.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.compensationResult").value("RESTORED"));
        assertThat(stock(couponId)).isEqualTo(QUANTITY);
        assertThat(redisTemplate.opsForSet().isMember(CouponRedisKeys.users(couponId), String.valueOf(memberId)))
                .isFalse();
        CouponIssueRequest rejected = couponIssueRequestRepository
                .findByCouponIdAndIssueRequestId(couponId, requestId).orElseThrow();
        assertThat(rejected.getResult()).isEqualTo(CouponIssueResult.REJECTED);
        issueStatus(couponId, memberId, requestId).andExpect(jsonPath("$.data.status").value("REJECTED"));

        // 다시 거절해도 보상 표식이 있어 재고는 그대로다
        reject(isolated.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.compensationResult").value("ALREADY_COMPENSATED"));
        assertThat(stock(couponId)).isEqualTo(QUANTITY);
        assertThat(issuedCount(couponId)).isZero();
    }

    /** 깨진 원문에 NUL이 있어도 격리 기록(text)이 저장돼 DLT로 넘어가고, 같은 쿠폰(partition)의 뒤 메시지가 처리된다 */
    @Test
    void NUL이_든_깨진_발급_메시지는_Base64_원문으로_격리되고_뒤_메시지는_처리된다() throws Exception {
        long couponId = openPlatformCoupon();
        byte[] broken = ("{\"issueRequestId\":\"nul-" + run + "\u0000\"").getBytes(StandardCharsets.UTF_8);
        Map<String, Object> configs = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(configs)) {
            ProducerRecord<String, byte[]> record = new ProducerRecord<>(CouponEventTopic.NAME,
                    String.valueOf(couponId), broken);
            record.headers().add("__TypeId__", CouponIssueRequested.class.getName().getBytes(StandardCharsets.UTF_8));
            producer.send(record).get(10, TimeUnit.SECONDS);
        }

        // 같은 쿠폰(key)의 뒤 접수가 확정되면 깨진 메시지의 offset을 지나간 것이다
        String requestId = "after-nul-" + run;
        requestIssue(couponId, 500L, requestId).andExpect(status().isAccepted());
        awaitResult(couponId, requestId, CouponIssueResult.ISSUED);
        assertThat(couponDltLogRepository.findAll()).filteredOn(log -> Long.valueOf(couponId).equals(log.getCouponId()))
                .singleElement().satisfies(log -> {
                    assertThat(log.getIssueRequestId()).isNull();
                    assertThat(log.getPayload())
                            .isEqualTo(CouponDltPayload.BASE64_PREFIX + Base64.getEncoder().encodeToString(broken));
                });
    }

    private long openPlatformCoupon() throws Exception {
        fundPromotion();
        Instant now = Instant.now();
        String body = """
                {"name":"선착순 할인","targetType":"ANY_LECTURE","discountType":"AMOUNT","discountValue":%d,"quantity":%d,
                 "issueStartAt":"%s","issueEndAt":"%s","expiryType":"RELATIVE","expiryDays":30,"stackable":false}
                """.formatted(DISCOUNT_HP, QUANTITY, now.minus(Duration.ofHours(1)), now.plus(Duration.ofDays(7)));
        String response = mockMvc.perform(post("/api/v1/coupons/admin")
                        .with(login(ADMIN_ID, "ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long couponId = ((Number) JsonPath.read(response, "$.data.couponId")).longValue();
        createdCouponIds.add(couponId);
        mockMvc.perform(post("/api/v1/coupons/admin/{couponId}/open", couponId).with(login(ADMIN_ID, "ADMIN")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OPEN"));
        return couponId;
    }

    private ResultActions requestIssue(long couponId, long memberId, String requestId) throws Exception {
        return mockMvc.perform(post("/api/v1/coupons/{couponId}/issue-requests", couponId)
                .with(login(memberId, "USER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"issueRequestId\":\"" + requestId + "\"}"));
    }

    private ResultActions issueStatus(long couponId, long memberId, String requestId) throws Exception {
        return mockMvc.perform(get("/api/v1/coupons/{couponId}/issue-requests/{requestId}", couponId, requestId)
                        .with(login(memberId, "USER")))
                .andExpect(status().isOk());
    }

    private ResultActions reject(long dltId) throws Exception {
        return mockMvc.perform(post("/api/v1/coupons/admin/dlt/{dltId}/reject", dltId)
                .with(login(ADMIN_ID, "ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"원인 확인 후 영구 거절\"}"));
    }

    private void awaitResult(long couponId, String requestId, CouponIssueResult expected) {
        await().atMost(WAIT).until(() -> couponIssueRequestRepository
                .findByCouponIdAndIssueRequestId(couponId, requestId)
                .map(CouponIssueRequest::getResult).orElse(null), result -> result == expected);
    }

    private String requestRecord(long couponId, String requestId) {
        return (String) redisTemplate.opsForHash().get(CouponRedisKeys.requests(couponId), requestId);
    }

    private int stock(long couponId) {
        return Integer.parseInt(redisTemplate.opsForValue().get(CouponRedisKeys.stock(couponId)));
    }

    private int issuedCount(long couponId) {
        return couponRepository.findById(couponId).orElseThrow().getIssuedCount();
    }

    /** 그룹별 DLT 토픽에 이 쿠폰(key)·요청의 메시지가 있는가. 처음부터 읽는다 */
    private boolean deadLetterContains(String key, String requestId) {
        Map<String, Object> configs = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(configs)) {
            List<TopicPartition> partitions = consumer.partitionsFor(CouponIssueConsumerKafkaConfig.DEAD_LETTER_TOPIC)
                    .stream().map(info -> new TopicPartition(info.topic(), info.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key()) && record.value() != null && record.value().contains(requestId)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /** 회원 ID가 이름(getName)인 로그인. 운영에서는 강의 앱의 Gateway 사용자 헤더 필터가 같은 모양으로 만든다 */
    private void fundPromotion() {
        org.springframework.web.client.RestClient.create(billing.baseUrl()).post()
                .uri("/api/v1/settlements/admin/promotion-wallet/fundings")
                .header("X-User-Id", String.valueOf(ADMIN_ID)).header("X-User-Role", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .body(java.util.Map.of("fundingWorkId", "coupon-e2e-" + java.util.UUID.randomUUID(),
                        "amountHp", 1_000_000L, "sourceType", "ADMIN_GRANT", "sourceRef", "coupon-e2e"))
                .retrieve().toBodilessEntity();
    }

    private static RequestPostProcessor login(long memberId, String role) {
        return user(String.valueOf(memberId)).roles(role);
    }

    /** 운영에서는 강의 앱 SecurityConfig가 켜는 메서드 보안. 이 모듈 테스트에는 그 설정이 없어 여기서 켠다 */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class MethodSecurityConfig {
    }
}
