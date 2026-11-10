package com.hapbang.lecture.in;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.fake.CommittedCouponOrderEvents;
import com.hapbang.lecture.fake.FakeBillingApiClient;
import com.hapbang.lecture.fake.FakeCouponApiClientConfig;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.lecture.support.GatewayLogin;
import com.hapbang.shared.billing.domain.BillingFailureCode;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.HoldResultDto;
import com.hapbang.shared.billing.out.BillingCallResult;
import com.hapbang.shared.lecture.event.LectureEventTopic;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.DatabaseCleanUpListener;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.KafkaTestcontainersConfig;
import com.hapbang.testsupport.ModuleTestConfiguration;
import com.hapbang.testsupport.PostgresTestcontainersConfig;
import com.hapbang.testsupport.RedisTestcontainersConfig;
import com.jayway.jsonpath.JsonPath;

/**
 * 판매자 쿠폰 주문을 강의 주문 API부터 쿠폰 모듈 DB까지 관통해 확인한다. 강의 → 쿠폰은 실제 쿠폰 내부 HTTP API(같은 서버의 포트)로,
 * 쿠폰 → 강의(강의 조회)도 실제 내부 HTTP API로, 강의 결제·취소 완료 이벤트는 실제 Kafka로 쿠폰 소비자에게 간다. 지갑만 Fake다.
 * <ol>
 *     <li>판매자 쿠폰 주문 → 보류 → 결제 완료 → 쿠폰 사용됨 → 구매 취소 → 반환 → 쿠폰 사용 가능. 같은 이벤트를 다시 보내도 효과는 한 번이다.
 *     사용 경로는 Redis를 쓰지 않으므로 Redis를 멈춘 채로 진행한다(I9).</li>
 *     <li>판매자 100% 쿠폰의 0 HP 주문 → 지갑 호출 없이 결제 완료 → 구매 취소 → 쿠폰 복원. 이어서 보류가 거절된 주문 → 보상 반환 → 쿠폰
 *     해제 → 쿠폰 사용 가능.</li>
 * </ol>
 * 쿠폰 발급(접수·발급 확정)만 Redis와 Kafka를 쓴다. 쿠폰 계산·예약·해제 규칙과 강의 쪽 단계별 처리는 각 모듈의 서비스 테스트가 맡는다.
 * <p>
 * 내부 API를 실제 HTTP로 부르려고 서버를 정해 둔 빈 포트로 띄운다. 쿠폰·강의 내부 API 주소 기본값이 그 포트를 쓴다.
 * 같은 소비 그룹의 다른 컨텍스트와 partition을 나누지 않게 클래스가 끝나면 컨텍스트를 닫는다.
 */
@SpringBootTest(classes = ModuleTestConfiguration.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                // IntegrationTest와 같은 모듈 테스트 설정
                "spring.config.name=hapbang-module-test",
                "spring.modulith.events.jdbc.schema-initialization.enabled=true",
                "spring.modulith.events.completion-mode=delete",
                "spring.security.oauth2.client.registration.google.client-id=test",
                "spring.security.oauth2.client.registration.google.client-secret=test",
                "hapbang.storage.s3.endpoint=http://localhost:9",
                "hapbang.storage.s3.bucket=test",
                "hapbang.storage.s3.region=ap-northeast-2",
                "hapbang.storage.s3.key-prefix=test",
                "hapbang.internal-api.key=" + IntegrationTest.INTERNAL_API_KEY,
                "hapbang.jwt.issuer=https://hapbang/member",
                "hapbang.jwt.access-token-ttl=15m",
                "hapbang.jwt.allow-ephemeral-key=true",
                // 이 테스트만: 실제 쿠폰 Client, 강의 이벤트 외부화, Kafka 리스너·토픽 선언
                FakeCouponApiClientConfig.ENABLED + "=false",
                "spring.modulith.events.externalization.enabled=true",
                "spring.kafka.listener.auto-startup=true",
                "spring.kafka.admin.auto-create=true",
                // 멈춘 Redis를 부르면 바로 실패하게 한다(사용 경로가 Redis를 부르지 않는지 드러난다)
                "spring.data.redis.timeout=1s",
                "spring.jpa.open-in-view=false"
        })
@AutoConfigureMockMvc
@Import({PostgresTestcontainersConfig.class, KafkaTestcontainersConfig.class, RedisTestcontainersConfig.class})
@TestExecutionListeners(listeners = DatabaseCleanUpListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@DirtiesContext
class LectureCouponOrderE2eTest {

    private static final Long BUYER_ID = OTHER_USER_ID;
    private static final long DISCOUNT = 3_000L;
    private static final String COUPON_ORDER_GROUP = "lecture-app.coupon-order";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int PORT = freePort();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private FakeBillingApiClient billing;

    @Autowired
    private CommittedCouponOrderEvents committedEvents;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final List<Long> createdCouponIds = new ArrayList<>();

    @DynamicPropertySource
    static void serverPort(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
    }

    @BeforeEach
    void setUp() {
        billing.reset();
        committedEvents.reset();
    }

    @AfterEach
    void cleanUpRedis() {
        // 공유 Redis 컨테이너에서 이 테스트가 만든 쿠폰 key만 지운다
        for (long couponId : createdCouponIds) {
            redisTemplate.delete(List.of("coupon:" + couponId + ":stock", "coupon:" + couponId + ":users",
                    "coupon:" + couponId + ":requests", "coupon:" + couponId + ":issue-control",
                    "coupon:" + couponId + ":sold_out"));
        }
    }

    @Test
    void 판매자_쿠폰_주문은_결제_완료에_사용됨이_되고_구매_취소_반환에_복원되며_같은_이벤트를_다시_받아도_한_번만_바뀐다() throws Exception {
        billing.setSettledHp(PRICE_HP - DISCOUNT);
        Lecture lecture = saveOnSale();
        long memberCouponId = issueSellerCoupon(lecture, DISCOUNT);
        long expected = quotedBuyerAmount(lecture, memberCouponId);
        assertThat(expected).isEqualTo(PRICE_HP - DISCOUNT);

        RedisTestcontainersConfig.withRedisPaused(() -> unchecked(() -> {
            String ordered = perform(order(lecture, memberCouponId, expected))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.status").value("PAID"))
                    .andExpect(jsonPath("$.data.buyerAmountHp").value(PRICE_HP - DISCOUNT))
                    .andReturn().getResponse().getContentAsString();
            long orderId = ((Number) JsonPath.read(ordered, "$.data.orderId")).longValue();
            long orderItemId = ((Number) JsonPath.read(ordered, "$.data.items[0].orderItemId")).longValue();
            assertThat(billing.calls()).extracting(FakeBillingApiClient.Call::command)
                    .singleElement().isInstanceOfSatisfying(HoldCommand.class,
                            hold -> assertThat(hold.amountHp()).isEqualTo(PRICE_HP - DISCOUNT));
            awaitMemberCoupon(memberCouponId, "USED");

            perform(post("/api/v1/lectures/orders/{orderId}/items/{orderItemId}/cancel", orderId, orderItemId)
                    .with(GatewayLogin.as(BUYER_ID, Role.USER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("CANCELED"));
            awaitMemberCoupon(memberCouponId, "AVAILABLE");

            // 같은 결제 완료·취소 완료 이벤트를 다시 보낸다. 소비가 끝나도 쿠폰은 사용 가능 그대로다
            assertThat(committedEvents.paid()).hasSize(1);
            assertThat(committedEvents.cancelCompleted()).hasSize(1);
            transactionTemplate.executeWithoutResult(status -> {
                eventPublisher.publishEvent(committedEvents.paid().getFirst());
                eventPublisher.publishEvent(committedEvents.cancelCompleted().getFirst());
            });
            awaitCouponOrderEventsConsumed();
            assertThat(memberCouponStatus(memberCouponId)).isEqualTo("AVAILABLE");
        }));
    }

    @Test
    void 판매자_100퍼센트_쿠폰의_0_HP_주문은_지갑_없이_결제되고_취소로_복원되며_보류가_거절된_주문은_보상_뒤_쿠폰을_해제한다()
            throws Exception {
        Lecture free = saveOnSale();
        long fullCoupon = issueSellerCoupon(free, PRICE_HP);

        String ordered = perform(order(free, fullCoupon, quotedBuyerAmount(free, fullCoupon)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PAID"))
                .andExpect(jsonPath("$.data.buyerAmountHp").value(0))
                .andReturn().getResponse().getContentAsString();
        assertThat(billing.calls()).isEmpty();
        awaitMemberCoupon(fullCoupon, "USED");
        perform(post("/api/v1/lectures/orders/{orderId}/items/{orderItemId}/cancel",
                ((Number) JsonPath.read(ordered, "$.data.orderId")).longValue(),
                ((Number) JsonPath.read(ordered, "$.data.items[0].orderItemId")).longValue())
                .with(GatewayLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED"));
        assertThat(billing.calls()).isEmpty();
        awaitMemberCoupon(fullCoupon, "AVAILABLE");

        // 쿠폰 강의(할인 3,000)와 쿠폰 없는 강의를 함께 주문: 첫 보류는 성공, 둘째 보류가 잔액 부족으로 거절 → 첫 보류 반환 → 쿠폰 해제
        Lecture discounted = saveOnSale();
        Lecture plain = saveOnSale();
        long coupon = issueSellerCoupon(discounted, DISCOUNT);
        long expected = PRICE_HP - DISCOUNT + PRICE_HP;
        billing.setSettledHp(PRICE_HP - DISCOUNT);
        billing.respond(command -> {
            HoldCommand hold = (HoldCommand) command;
            return new BillingCallResult.Succeeded<>(new HoldResultDto(hold.referenceType(), hold.referenceId(),
                    hold.amountHp(), false));
        });
        billing.respond(command -> new BillingCallResult.Failed<>(BillingFailureCode.INSUFFICIENT_BALANCE,
                "HP 잔액이 부족합니다.", 409, "INSUFFICIENT_BALANCE"));

        perform(post("/api/v1/lectures/orders").with(GatewayLogin.as(BUYER_ID, Role.USER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"lectureIds": [%d, %d], "expectedBuyerAmountHp": %d,
                         "coupons": [{"memberCouponId": %d, "lectureId": %d}]}
                        """.formatted(discounted.getId(), plain.getId(), expected, coupon, discounted.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));

        // 주문 요청 안에서 보상 반환까지 끝나고 결제 실패가 확정되어 쿠폰 해제를 보냈다
        assertThat(memberCouponStatus(coupon)).isEqualTo("AVAILABLE");
    }

    // ---------------------------------------------------------------------------------------------

    /** 판매자가 강의 대상 정액 쿠폰을 신청 → 심사 요청 → 관리자 승인(발급 열림) → 구매자가 발급 접수 → 발급 확정까지 기다린다 */
    private long issueSellerCoupon(Lecture lecture, long discountHp) throws Exception {
        Instant now = Instant.now();
        String created = perform(post("/api/v1/coupons/seller").with(GatewayLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"판매자 할인","targetType":"LECTURE","lectureId":%d,"discountType":"AMOUNT",
                         "discountValue":%d,"quantity":10,"issueStartAt":"%s","issueEndAt":"%s",
                         "expiryType":"RELATIVE","expiryDays":30,"stackable":false}
                        """.formatted(lecture.getId(), discountHp, now.minus(Duration.ofHours(1)),
                        now.plus(Duration.ofDays(7)))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long couponId = ((Number) JsonPath.read(created, "$.data.couponId")).longValue();
        createdCouponIds.add(couponId);
        perform(post("/api/v1/coupons/seller/{couponId}/review", couponId)
                .with(GatewayLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)))
                .andExpect(status().isOk());
        perform(post("/api/v1/coupons/admin/{couponId}/approve", couponId).with(GatewayLogin.as(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OPEN"));
        perform(post("/api/v1/coupons/{couponId}/issue-requests", couponId).with(GatewayLogin.as(BUYER_ID, Role.USER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"issueRequestId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isAccepted());
        await().atMost(TIMEOUT).until(() -> memberCouponIdOf(couponId) != null);
        return memberCouponIdOf(couponId);
    }

    /** 회원용 계산 미리보기의 구매자 금액 합. 주문은 이 값을 {@code expectedBuyerAmountHp}로 보낸다 */
    private long quotedBuyerAmount(Lecture lecture, long memberCouponId) throws Exception {
        String quoted = perform(post("/api/v1/coupons/me/quote").with(GatewayLogin.as(BUYER_ID, Role.USER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"lectureIds": [%d], "selections": [{"memberCouponId": %d, "lectureId": %d}]}
                        """.formatted(lecture.getId(), memberCouponId, lecture.getId())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(quoted, "$.data.totalBuyerAmount")).longValue();
    }

    private MockHttpServletRequestBuilder order(Lecture lecture, long memberCouponId, long expected) {
        return post("/api/v1/lectures/orders").with(GatewayLogin.as(BUYER_ID, Role.USER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"lectureIds": [%d], "expectedBuyerAmountHp": %d,
                         "coupons": [{"memberCouponId": %d, "lectureId": %d}]}
                        """.formatted(lecture.getId(), expected, memberCouponId, lecture.getId()));
    }

    private void awaitMemberCoupon(long memberCouponId, String status) {
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(memberCouponStatus(memberCouponId)).isEqualTo(status));
    }

    private String memberCouponStatus(long memberCouponId) throws Exception {
        List<String> statuses = JsonPath.read(myCoupons(), "$.data[?(@.memberCouponId == " + memberCouponId + ")].status");
        return statuses.isEmpty() ? null : statuses.getFirst();
    }

    private Long memberCouponIdOf(long couponId) throws Exception {
        List<Number> ids = JsonPath.read(myCoupons(), "$.data[?(@.couponId == " + couponId + ")].memberCouponId");
        return ids.isEmpty() ? null : ids.getFirst().longValue();
    }

    private String myCoupons() throws Exception {
        return perform(get("/api/v1/coupons/me").with(GatewayLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** 쿠폰 주문 소비 그룹이 강의 토픽의 끝까지 읽고 커밋했을 때까지 기다린다(다시 보낸 이벤트까지 처리했다) */
    private void awaitCouponOrderEventsConsumed() {
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            await().atMost(TIMEOUT).until(() -> consumedToEnd(admin));
        }
    }

    private static boolean consumedToEnd(AdminClient admin) throws ExecutionException, InterruptedException {
        Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(COUPON_ORDER_GROUP)
                .partitionsToOffsetAndMetadata().get();
        List<TopicPartition> partitions = admin.describeTopics(List.of(LectureEventTopic.NAME)).allTopicNames().get()
                .get(LectureEventTopic.NAME).partitions().stream()
                .map(partition -> new TopicPartition(LectureEventTopic.NAME, partition.partition()))
                .toList();
        Map<TopicPartition, Long> ends = admin.listOffsets(partitions.stream()
                        .collect(Collectors.toMap(partition -> partition, partition -> OffsetSpec.latest())))
                .all().get().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().offset()));
        return partitions.stream().allMatch(partition -> ends.get(partition) == 0
                || (committed.get(partition) != null && committed.get(partition).offset() >= ends.get(partition)));
    }

    /** Redis를 멈춘 동안의 단계는 {@link Runnable}이라 검사 예외를 감싼다 */
    private static void unchecked(ThrowingRunnable action) {
        try {
            action.run();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private ResultActions perform(RequestBuilder request) {
        try {
            return mockMvc.perform(request);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Lecture saveOnSale() {
        Lecture lecture = Lecture.register(SELLER_ID, TITLE, DESCRIPTION, PRICE_HP, null, contentSpecs(), NOW);
        lecture.approve(ADMIN_ID, NOW);
        return lectureRepository.save(lecture);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
