package com.hapbang.coupon.app;

import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponType;
import com.hapbang.coupon.domain.CouponUsage;
import com.hapbang.coupon.domain.CouponUsageStatus;
import com.hapbang.coupon.domain.MemberCoupon;
import com.hapbang.coupon.domain.MemberCouponStatus;
import com.hapbang.coupon.domain.usage.AppliedCoupon;
import com.hapbang.coupon.domain.usage.UsageItem;
import com.hapbang.coupon.out.CouponRepository;
import com.hapbang.coupon.out.CouponUsageRepository;
import com.hapbang.coupon.out.MemberCouponRepository;
import com.hapbang.shared.lecture.event.OrderItemCancelCompletedEvent;
import com.hapbang.shared.lecture.event.OrderItemPaidEvent;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.RedisTestcontainersConfig;

import jakarta.persistence.EntityManager;

/**
 * 같은 주문 항목의 두 소비가 겹칠 때 사용 시도 행 잠금으로 차례로 처리되는지 실제 DB로 결정적으로 본다. 취소 완료 소비가 잠금을 쥔 채
 * 멈춘 동안 결제 완료(관리자 DLT 재처리가 부르는 것과 같은 UseCase)는 잠금을 기다리고, 취소가 커밋한 뒤 커밋된 상태를 읽어 사실만 남긴다.
 * 잠그지 않으면 결제 쪽이 먼저 읽은 RESERVED 엔티티를 전체 컬럼으로 UPDATE해 취소 결과를 덮어쓴다.
 */
@IntegrationTest
@Import(RedisTestcontainersConfig.class)
class CouponOrderConsumerLockTest {

    private static final long ORDER_ID = 9401L;
    private static final long ORDER_ITEM_ID = 94011L;
    private static final long PRICE = 10_000L;
    private static final Duration WAIT = Duration.ofSeconds(15);

    @Autowired
    private CouponOrderConsumerUseCase couponOrderConsumerUseCase;

    @MockitoSpyBean
    private MemberCouponRepository memberCouponRepository;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private CouponUsageRepository couponUsageRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManager entityManager;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void 취소_완료_소비가_잠금을_쥔_동안_결제_완료는_기다렸다가_취소_결과를_덮어쓰지_않고_사실만_남긴다() throws Exception {
        Instant now = Instant.now();
        long memberCouponId = reservedUsage(now);
        OrderItemCancelCompletedEvent canceled = OrderItemCancelCompletedEvent.of(ORDER_ID, ORDER_ITEM_ID, MEMBER_ID,
                List.of(new OrderItemCancelCompletedEvent.Usage(memberCouponId, 1)), null, List.of(), now);
        OrderItemPaidEvent paid = OrderItemPaidEvent.of(ORDER_ID, ORDER_ITEM_ID, MEMBER_ID,
                List.of(new OrderItemPaidEvent.Usage(memberCouponId, 1)), now);

        // 취소 소비가 사용 시도를 잠근 뒤 발급 쿠폰을 읽는 첫 자리에서 멈춘다
        CountDownLatch cancelLocked = new CountDownLatch(1);
        CountDownLatch resumeCancel = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        // 저장소는 인터페이스(JDK 프록시)라 callRealMethod를 쓸 수 없다. spy의 기본 응답(원래 빈에 위임)으로 실제 조회를 한다
        Answer<?> delegate = mockingDetails(memberCouponRepository).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            if (first.compareAndSet(true, false)) {
                cancelLocked.countDown();
                assertThat(resumeCancel.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
            }
            return delegate.answer(invocation);
        }).when(memberCouponRepository).findStateById(anyLong());

        Future<CouponOrderConsumerUseCase.Result> cancelResult =
                executor.submit(() -> couponOrderConsumerUseCase.consume(canceled));
        assertThat(cancelLocked.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        Future<CouponOrderConsumerUseCase.Result> paidResult =
                executor.submit(() -> couponOrderConsumerUseCase.consume(paid));

        // 결제 소비가 사용 시도 행 잠금을 기다리는 것을 DB에서 확인한 뒤 취소를 마저 진행한다
        await().atMost(WAIT).until(() -> lockWaiters() == 1);
        assertThat(paidResult.isDone()).isFalse();
        resumeCancel.countDown();

        assertThat(cancelResult.get(WAIT.toSeconds(), TimeUnit.SECONDS))
                .isEqualTo(CouponOrderConsumerUseCase.Result.APPLIED);
        assertThat(paidResult.get(WAIT.toSeconds(), TimeUnit.SECONDS))
                .isEqualTo(CouponOrderConsumerUseCase.Result.FACT_ONLY);
        CouponUsage usage = couponUsageRepository.findByMemberCouponIdAndGeneration(memberCouponId, 1).orElseThrow();
        assertThat(usage.getStatus()).isEqualTo(CouponUsageStatus.CANCELED);
        assertThat(usage.getCancelEventId()).isEqualTo(canceled.eventId());
        assertThat(usage.getRestoredTo()).isEqualTo(MemberCouponStatus.AVAILABLE);
        assertThat(usage.getRestoredAt()).isNotNull();
        assertThat(usage.getPaidEventId()).isEqualTo(paid.eventId());
        assertThat(memberCouponRepository.findStateById(memberCouponId).orElseThrow().status())
                .isEqualTo(MemberCouponStatus.AVAILABLE);
    }

    /**
     * 관리자 재처리 경로만 트랜잭션 제한 시간을 둔다. 다른 트랜잭션이 사용 시도 행 잠금을 쥔 동안 Kafka 소비 경로와 재처리 경로가 함께
     * 기다리면, 재처리 경로는 제한 시간(1초)에 끊겨 되돌아가고 Kafka 소비 경로는 계속 기다렸다가 잠금이 풀린 뒤 반영한다.
     */
    @Test
    void 행_잠금을_오래_기다리면_재처리_경로만_제한_시간에_끊기고_Kafka_소비_경로는_계속_기다린다() throws Exception {
        Instant now = Instant.now();
        long memberCouponId = reservedUsage(now);
        OrderItemPaidEvent paid = OrderItemPaidEvent.of(ORDER_ID, ORDER_ITEM_ID, MEMBER_ID,
                List.of(new OrderItemPaidEvent.Usage(memberCouponId, 1)), now);

        // 다른 트랜잭션이 주문 항목의 사용 시도 행 잠금을 쥔 채 멈춘다
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            couponUsageRepository.findForUpdateByOrderItemId(ORDER_ITEM_ID);
            locked.countDown();
            try {
                assertThat(release.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(locked.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        Future<CouponOrderConsumerUseCase.Result> kafkaPath = executor.submit(() -> couponOrderConsumerUseCase.consume(paid));
        await().atMost(WAIT).until(() -> lockWaiters() == 1);

        long started = System.nanoTime();
        assertThatThrownBy(() -> couponOrderConsumerUseCase.consumeWithin(paid, 1))
                .isInstanceOf(QueryTimeoutException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofSeconds(1));
        // 재처리 경로가 끊긴 뒤에도 Kafka 소비 경로는 여전히 기다린다
        assertThat(kafkaPath.isDone()).isFalse();

        release.countDown();
        holder.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(kafkaPath.get(WAIT.toSeconds(), TimeUnit.SECONDS)).isEqualTo(CouponOrderConsumerUseCase.Result.APPLIED);
        assertThat(couponUsageRepository.findByMemberCouponIdAndGeneration(memberCouponId, 1).orElseThrow().getStatus())
                .isEqualTo(CouponUsageStatus.PAID);
    }

    /** 판매자 쿠폰 한 장을 주문 항목에 예약한 상태(발급 쿠폰 RESERVED·generation 1, 사용 시도 RESERVED). 발급 쿠폰 ID */
    private long reservedUsage(Instant now) {
        Long couponId = couponRepository.save(Coupon.createSeller(SELLER_ID, lectureRate().build(), now)).getId();
        MemberCoupon memberCoupon = memberCouponRepository.save(MemberCoupon.issue(MEMBER_ID, couponId, "req-lock",
                now, now.plus(Duration.ofDays(30)), now));
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                memberCouponRepository.reserve(memberCoupon.getId(), MEMBER_ID, 0, ORDER_ITEM_ID, now));
        couponUsageRepository.save(CouponUsage.reserve(MEMBER_ID, ORDER_ID,
                new UsageItem(ORDER_ITEM_ID, LECTURE_ID, SELLER_ID, PRICE),
                new AppliedCoupon(memberCoupon.getId(), couponId, CouponType.SELLER, 1, PRICE, 1_000, 0, 1_000, 0, 1),
                "lecture-order:" + ORDER_ID + ":reserve", now));
        return memberCoupon.getId();
    }

    /** 이 database에서 행 잠금을 기다리는 세션 수 */
    private long lockWaiters() {
        return ((Number) entityManager.createNativeQuery("""
                select count(*) from pg_stat_activity
                where datname = current_database() and wait_event_type = 'Lock'
                """).getSingleResult()).longValue();
    }
}
