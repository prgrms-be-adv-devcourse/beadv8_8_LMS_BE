package com.hapbang.coupon.app;

import static com.hapbang.coupon.app.CouponAppFixture.*;
import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponIssueRequest;
import com.hapbang.coupon.domain.CouponIssueResult;
import com.hapbang.coupon.domain.IssueDelivery;
import com.hapbang.coupon.domain.IssueRejectReason;
import com.hapbang.coupon.domain.MemberCoupon;
import com.hapbang.coupon.domain.ProcessedOperation;
import com.hapbang.coupon.out.CouponIssueRequestRepository;
import com.hapbang.coupon.out.CouponRepository;
import com.hapbang.coupon.out.MemberCouponRepository;
import com.hapbang.coupon.out.ProcessedOperationRepository;
import com.hapbang.shared.coupon.event.CouponIssueRequested;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

// 발급 확정의 분기: 정상, 재전달 멱등, 내용 충돌, epoch 불일치, 총수량 초과 롤백, 유일 제약 위반 뒤 처리.
// 조건부 UPDATE·유일 제약이 실제 DB에서 맞는지는 리포지토리 테스트, 같은 메시지 재전달의 발급 1건은 e2e가 본다
class CouponIssueConsumerUseCaseTest {

    private static final String REQUEST_ID = "req-1";
    private static final Instant ISSUED_AT = NOW.minusSeconds(5);
    private static final Instant EXPIRES_AT = ISSUED_AT.plus(Duration.ofDays(30));
    private static final long MEMBER_COUPON_ID = 900L;

    private final CouponRepository couponRepository = mock();
    private final MemberCouponRepository memberCouponRepository = mock();
    private final CouponIssueRequestRepository issueRequestRepository = mock();
    private final ProcessedOperationRepository processedOperationRepository = mock();
    private final PlatformTransactionManager transactionManager = mock();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private CouponIssueConsumerUseCase useCase;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        CouponIssueConsumerMetrics metrics = new CouponIssueConsumerMetrics(providerOf(meterRegistry));
        useCase = new CouponIssueConsumerUseCase(couponRepository, memberCouponRepository, issueRequestRepository,
                processedOperationRepository, metrics, transactionManager, CLOCK);
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(openSellerCoupon()));
        when(processedOperationRepository.findByOperationId(anyString())).thenReturn(Optional.empty());
        when(issueRequestRepository.findByCouponIdAndIssueRequestId(anyLong(), anyString())).thenReturn(Optional.empty());
        when(memberCouponRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            MemberCoupon saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", MEMBER_COUPON_ID);
            return saved;
        });
        when(couponRepository.incrementIssuedCount(eq(COUPON_ID), anyLong())).thenReturn(1);
    }

    private static CouponIssueRequested event(long memberId, long epoch) {
        return new CouponIssueRequested(REQUEST_ID, COUPON_ID, memberId, epoch, ISSUED_AT, EXPIRES_AT);
    }

    private static IssueDelivery delivery(long memberId, long epoch) {
        return new IssueDelivery(REQUEST_ID, COUPON_ID, memberId, epoch, ISSUED_AT, EXPIRES_AT);
    }

    @Test
    void 발급_쿠폰_발급_결과_멱등_기록을_넣고_확정_발급_수를_올린다() {
        CouponIssueConsumerUseCase.Result result = useCase.consume(event(MEMBER_ID, Coupon.INITIAL_EPOCH));

        assertThat(result).isEqualTo(CouponIssueConsumerUseCase.Result.ISSUED);
        ArgumentCaptor<MemberCoupon> memberCoupon = ArgumentCaptor.forClass(MemberCoupon.class);
        verify(memberCouponRepository).saveAndFlush(memberCoupon.capture());
        assertThat(memberCoupon.getValue().getExpiresAt()).isEqualTo(EXPIRES_AT);
        ArgumentCaptor<CouponIssueRequest> issueRequest = ArgumentCaptor.forClass(CouponIssueRequest.class);
        verify(issueRequestRepository).save(issueRequest.capture());
        assertThat(issueRequest.getValue().getResult()).isEqualTo(CouponIssueResult.ISSUED);
        assertThat(issueRequest.getValue().getMemberCouponId()).isEqualTo(MEMBER_COUPON_ID);
        ArgumentCaptor<ProcessedOperation> processed = ArgumentCaptor.forClass(ProcessedOperation.class);
        verify(processedOperationRepository).save(processed.capture());
        assertThat(processed.getValue().getOperationId()).isEqualTo("coupon-issue:" + COUPON_ID + ":" + REQUEST_ID);
        verify(couponRepository).incrementIssuedCount(COUPON_ID, Coupon.INITIAL_EPOCH);
        verify(transactionManager).commit(any());
        assertThat(meterRegistry.counter(CouponIssueConsumerMetrics.CONSUME, "result", "issued").count()).isEqualTo(1);
    }

    @Test
    void 같은_요청이_다시_오면_아무것도_바꾸지_않고_성공으로_끝낸다() {
        when(processedOperationRepository.findByOperationId("coupon-issue:" + COUPON_ID + ":" + REQUEST_ID))
                .thenReturn(Optional.of(ProcessedOperation.of(delivery(MEMBER_ID, 1), CouponIssueResult.ISSUED, NOW)));

        assertThat(useCase.consume(event(MEMBER_ID, 1))).isEqualTo(CouponIssueConsumerUseCase.Result.DUPLICATE);

        verify(memberCouponRepository, never()).saveAndFlush(any());
        verify(couponRepository, never()).incrementIssuedCount(anyLong(), anyLong());
    }

    @Test
    void 영구_거절처럼_소비자_밖에서_확정된_같은_요청도_다시_반영하지_않는다() {
        when(issueRequestRepository.findByCouponIdAndIssueRequestId(COUPON_ID, REQUEST_ID))
                .thenReturn(Optional.of(delivery(MEMBER_ID, 1).toRejected("관리자 거절", NOW)));

        assertThat(useCase.consume(event(MEMBER_ID, 1))).isEqualTo(CouponIssueConsumerUseCase.Result.DUPLICATE);

        verify(memberCouponRepository, never()).saveAndFlush(any());
    }

    @Test
    void 같은_요청_ID에_다른_내용이_오면_격리한다() {
        when(processedOperationRepository.findByOperationId(anyString()))
                .thenReturn(Optional.of(ProcessedOperation.of(delivery(MEMBER_ID, 1), CouponIssueResult.ISSUED, NOW)));

        assertThatThrownBy(() -> useCase.consume(event(OTHER_MEMBER_ID, 1)))
                .isInstanceOf(CouponIssueConsumerIsolationException.class)
                .hasMessageContaining("다른 내용");
        verify(memberCouponRepository, never()).saveAndFlush(any());
    }

    @Test
    void 쿠폰의_지금_epoch와_다른_요청은_새_재고에_반영하지_않고_격리한다() {
        Coupon coupon = openSellerCoupon();
        ReflectionTestUtils.setField(coupon, "epoch", 2L);
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon));

        assertThatThrownBy(() -> useCase.consume(event(MEMBER_ID, 1)))
                .isInstanceOf(CouponIssueConsumerIsolationException.class)
                .hasMessageContaining("STALE");
        verify(memberCouponRepository, never()).saveAndFlush(any());
        verify(couponRepository, never()).incrementIssuedCount(anyLong(), anyLong());
    }

    @Test
    void 없는_쿠폰이거나_형식이_틀린_요청은_격리한다() {
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.consume(event(MEMBER_ID, 1)))
                .isInstanceOf(CouponIssueConsumerIsolationException.class);
        assertThatThrownBy(() -> useCase.consume(new CouponIssueRequested(REQUEST_ID, COUPON_ID, MEMBER_ID, 1, null,
                EXPIRES_AT))).isInstanceOf(CouponIssueConsumerIsolationException.class);
    }

    @Test
    void 확정_발급_수가_이미_총수량이면_발급을_되돌리고_새_트랜잭션에서_총수량_초과로_거절한다() {
        when(couponRepository.incrementIssuedCount(eq(COUPON_ID), anyLong())).thenReturn(0);

        CouponIssueConsumerUseCase.Result result = useCase.consume(event(MEMBER_ID, 1));

        assertThat(result).isEqualTo(CouponIssueConsumerUseCase.Result.REJECTED_OVER_QUANTITY);
        verify(transactionManager).rollback(any());
        ArgumentCaptor<CouponIssueRequest> saved = ArgumentCaptor.forClass(CouponIssueRequest.class);
        verify(issueRequestRepository, times(2)).save(saved.capture());
        CouponIssueRequest rejected = saved.getAllValues().getLast();
        assertThat(rejected.getResult()).isEqualTo(CouponIssueResult.REJECTED);
        assertThat(rejected.getRejectReason()).isEqualTo(IssueRejectReason.OVER_QUANTITY.getMessage());
        ArgumentCaptor<ProcessedOperation> processed = ArgumentCaptor.forClass(ProcessedOperation.class);
        verify(processedOperationRepository, times(2)).save(processed.capture());
        assertThat(processed.getAllValues().getLast().getResult()).isEqualTo("REJECTED");
    }

    @Test
    void 쿠폰을_읽은_뒤_복구_재개로_epoch가_바뀌면_발급을_되돌리고_거절하지_않고_격리한다() {
        Coupon resumed = openSellerCoupon();
        ReflectionTestUtils.setField(resumed, "epoch", 2L);
        // 처음 읽을 때는 epoch 1, 조건부 UPDATE는 epoch가 바뀌어 0행, 다시 읽으면 epoch 2
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(openSellerCoupon()), Optional.of(resumed));
        when(couponRepository.incrementIssuedCount(eq(COUPON_ID), anyLong())).thenReturn(0);

        assertThatThrownBy(() -> useCase.consume(event(MEMBER_ID, 1)))
                .isInstanceOf(CouponIssueConsumerIsolationException.class)
                .hasMessageContaining("STALE");
        verify(transactionManager, times(2)).rollback(any());
        verify(transactionManager, never()).commit(any());
        // 발급 쿠폰·발급 결과는 첫 트랜잭션과 함께 되돌렸고 거절 결과도 남기지 않는다
        verify(issueRequestRepository, times(1)).save(any());
    }

    @Test
    void epoch가_바뀌어_되돌린_뒤_같은_요청의_결과가_이미_있으면_그_결과를_재생한다() {
        Coupon resumed = openSellerCoupon();
        ReflectionTestUtils.setField(resumed, "epoch", 2L);
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(openSellerCoupon()), Optional.of(resumed));
        when(couponRepository.incrementIssuedCount(eq(COUPON_ID), anyLong())).thenReturn(0);
        when(processedOperationRepository.findByOperationId(anyString())).thenReturn(Optional.empty(),
                Optional.of(ProcessedOperation.of(delivery(MEMBER_ID, 1), CouponIssueResult.ISSUED, NOW)));

        assertThat(useCase.consume(event(MEMBER_ID, 1))).isEqualTo(CouponIssueConsumerUseCase.Result.DUPLICATE);
    }

    @Test
    void 같은_요청이_동시에_먼저_확정되어_유일_제약에_걸리면_저장된_결과를_다시_읽어_성공으로_끝낸다() {
        doThrow(PostgresUniqueViolation.of(MemberCouponRepository.MEMBER_COUPON_UNIQUE))
                .when(memberCouponRepository).saveAndFlush(any());
        when(issueRequestRepository.findByCouponIdAndIssueRequestId(COUPON_ID, REQUEST_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(delivery(MEMBER_ID, 1).toIssued(MEMBER_COUPON_ID, NOW)));

        assertThat(useCase.consume(event(MEMBER_ID, 1))).isEqualTo(CouponIssueConsumerUseCase.Result.DUPLICATE);

        verify(transactionManager).rollback(any());
        verify(couponRepository, never()).incrementIssuedCount(anyLong(), anyLong());
    }

    @Test
    void 같은_회원이_다른_요청으로_이미_받았으면_이_요청을_이미_발급된_회원으로_거절한다() {
        doThrow(PostgresUniqueViolation.of(MemberCouponRepository.MEMBER_COUPON_UNIQUE))
                .when(memberCouponRepository).saveAndFlush(any());
        when(memberCouponRepository.findByMemberIdAndCouponId(MEMBER_ID, COUPON_ID))
                .thenReturn(Optional.of(MemberCoupon.issue(MEMBER_ID, COUPON_ID, "other-req", ISSUED_AT, EXPIRES_AT, NOW)));

        assertThat(useCase.consume(event(MEMBER_ID, 1)))
                .isEqualTo(CouponIssueConsumerUseCase.Result.REJECTED_MEMBER_ALREADY_ISSUED);

        ArgumentCaptor<CouponIssueRequest> saved = ArgumentCaptor.forClass(CouponIssueRequest.class);
        verify(issueRequestRepository).save(saved.capture());
        assertThat(saved.getValue().getRejectReason()).isEqualTo(IssueRejectReason.MEMBER_ALREADY_ISSUED.getMessage());
    }

    @Test
    void 예상하지_못한_무결성_위반은_그대로_던져_재시도하게_한다() {
        DataIntegrityViolationException unexpected = PostgresUniqueViolation.of("uk_unexpected");
        doThrow(unexpected).when(memberCouponRepository).saveAndFlush(any());

        assertThatThrownBy(() -> useCase.consume(event(MEMBER_ID, 1))).isSameAs(unexpected);
        verify(issueRequestRepository, never()).save(any());
    }

    @SuppressWarnings("unchecked")
    static <T> org.springframework.beans.factory.ObjectProvider<T> providerOf(T value) {
        org.springframework.beans.factory.ObjectProvider<T> provider = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable(any())).thenReturn(value);
        return provider;
    }
}
