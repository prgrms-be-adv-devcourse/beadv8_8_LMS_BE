package com.hapbang.coupon.app;

import static com.hapbang.coupon.app.CouponIssueRequestUseCaseTest.registry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.hapbang.coupon.app.port.CouponIssueEventPublisher;
import com.hapbang.coupon.app.port.CouponIssueEventPublisher.PublishResult;
import com.hapbang.coupon.app.port.CouponIssueStore;
import com.hapbang.coupon.app.port.CouponIssueStore.IssueControlState;
import com.hapbang.coupon.app.port.CouponIssueStore.RequestEntry;
import com.hapbang.coupon.app.port.CouponIssueStoreUnavailableException;
import com.hapbang.coupon.app.CouponRepublishUseCase.CouponResult;
import com.hapbang.coupon.app.CouponRepublishUseCase.Ending;
import com.hapbang.coupon.app.CouponRepublishUseCase.RepublishSummary;
import com.hapbang.coupon.config.CouponIssueDeliveryProperties;
import com.hapbang.coupon.domain.CouponStatus;
import com.hapbang.coupon.domain.IssueRequestRecord;
import com.hapbang.coupon.out.CouponDltLogRepository;
import com.hapbang.coupon.out.CouponRepublishQueryRepository;
import com.hapbang.coupon.out.RepublishTargetCoupon;
import com.hapbang.coupon.out.redis.CouponRedisProperties;
import com.hapbang.shared.coupon.event.CouponIssueRequested;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

// 재발행 흐름: 어떤 쿠폰·요청을 다시 보내는지(DB 부재만, DB 존재·DLT 미처리·보상 완료 제외, 복구 중 쿠폰 제외, 전송 직전 차단 확인)와
// 실린 값, 연속 실패 때 그 쿠폰의 남은 전송 멈춤, 미확정 요청이 남은 쿠폰의 key 만료 연장을 본다.
// 요청 하나의 판정 규칙은 RepublishRuleTest, HSCAN·기록 형식은 Redis 테스트가 본다
class CouponRepublishUseCaseTest {

    private static final long COUPON_ID = 42;
    private static final long EPOCH = 2;
    private static final Instant NOW = Instant.parse("2026-11-08T03:00:00Z");
    private static final Instant OLD = NOW.minus(Duration.ofMinutes(5));
    private static final Instant EXPIRES_AT = OLD.plus(Duration.ofDays(30));
    /** 지금 + key 만료 여유(기본 7일) */
    private static final Instant RETAIN_UNTIL = NOW.plus(Duration.ofDays(7));

    private final CouponRepublishQueryRepository queryRepository = mock();
    private final CouponDltLogRepository dltLogRepository = mock();
    private final CouponIssueStore issueStore = mock();
    private final CouponIssueEventPublisher publisher = mock();
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private CouponRepublishUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new CouponRepublishUseCase(queryRepository, dltLogRepository, issueStore, publisher,
                CouponIssueDeliveryProperties.defaults(), new CouponRedisProperties(null), registry(meterRegistry),
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(publisher.publish(any(), any())).thenReturn(PublishResult.ACKED);
        when(issueStore.findIssueControl(anyLong())).thenReturn(Optional.of(new IssueControlState(true, "OPEN", EPOCH, null)));
        when(issueStore.hasRequests(anyLong())).thenReturn(true);
        when(queryRepository.findRecordedIds(anyLong(), any())).thenReturn(List.of());
        when(dltLogRepository.findUnresolvedRequestIds(anyLong())).thenReturn(List.of());
    }

    @Test
    void DB에_없는_요청만_같은_ID와_원래_접수_값과_지금_epoch로_다시_보내고_DB에_있는_요청은_건너뛴다() {
        target(CouponStatus.OPEN);
        requests(Map.of(
                "req-missing", accepted(7, OLD) + "|PUBLISHED",
                "req-recorded", accepted(8, OLD)));
        when(queryRepository.findRecordedIds(eq(COUPON_ID), any())).thenReturn(List.of("req-recorded"));

        var summary = useCase.republishDue();

        assertCounts(summary, 1, 1, 0);
        verify(publisher).publish(new CouponIssueRequested("req-missing", COUPON_ID, 7, EPOCH, OLD, EXPIRES_AT),
                Duration.ofSeconds(3));
        verifyNoMoreInteractions(publisher);
        assertThat(meterRegistry.get(CouponRepublishUseCase.REPUBLISHED_METRIC).tag("result", "acked").counter().count())
                .isEqualTo(1);
        assertThat(meterRegistry.get(CouponRepublishUseCase.OLDEST_AGE_METRIC).gauge().value()).isEqualTo(300);
        assertThat(meterRegistry.get(CouponRepublishUseCase.LAST_REPUBLISHED_METRIC).gauge().value())
                .isEqualTo(NOW.getEpochSecond());
    }

    @Test
    void 접수_기록에_접수_epoch가_있으면_그_epoch를_싣고_옛_형식이면_지금_epoch를_싣고_이전_epoch_기록은_보내지_않고_센다() {
        target(CouponStatus.OPEN);
        requests(Map.of(
                "req-new", IssueRequestRecord.accepted(7, OLD, EXPIRES_AT, EPOCH).format() + "|PUBLISHED",
                "req-old", accepted(8, OLD),
                "req-stale", IssueRequestRecord.accepted(9, OLD, EXPIRES_AT, EPOCH - 1).format()));

        useCase.republishDue();

        verify(publisher).publish(new CouponIssueRequested("req-new", COUPON_ID, 7, EPOCH, OLD, EXPIRES_AT),
                Duration.ofSeconds(3));
        verify(publisher).publish(new CouponIssueRequested("req-old", COUPON_ID, 8, EPOCH, OLD, EXPIRES_AT),
                Duration.ofSeconds(3));
        verifyNoMoreInteractions(publisher);
        assertThat(meterRegistry.get(CouponRepublishUseCase.STALE_EPOCH_METRIC).counter().count()).isEqualTo(1.0);
    }

    @Test
    void 대기_시간_전이나_보상_완료나_DLT_PENDING_요청은_보내지_않고_DB는_대기가_지난_후보만_확인한다() {
        target(CouponStatus.SUSPENDED);
        requests(Map.of(
                "req-fresh", accepted(7, NOW.minusSeconds(10)),
                "req-compensated", accepted(8, OLD) + "|COMPENSATED",
                "req-dlt", accepted(9, OLD),
                "req-broken", "broken"));
        when(dltLogRepository.findUnresolvedRequestIds(COUPON_ID)).thenReturn(List.of("req-dlt"));

        var summary = useCase.republishDue();

        assertThat(summary.republished()).isZero();
        verifyNoInteractions(publisher);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> checked = ArgumentCaptor.forClass(Collection.class);
        verify(queryRepository).findRecordedIds(eq(COUPON_ID), checked.capture());
        assertThat(checked.getValue()).containsExactly("req-dlt");
        assertThat(meterRegistry.get(CouponRepublishUseCase.OLDEST_AGE_METRIC).gauge().value()).isZero();
    }

    @Test
    void 전송_직전에_접수가_차단되면_그_쿠폰의_남은_후보를_보내지_않는다() {
        target(CouponStatus.OPEN);
        requestPages(List.of(
                Map.of("req-1", accepted(7, OLD), "req-2", accepted(8, OLD)),
                Map.of("req-3", accepted(9, OLD))));
        IssueControlState ready = new IssueControlState(true, "OPEN", EPOCH, null);
        when(issueStore.findIssueControl(COUPON_ID)).thenReturn(Optional.of(ready), Optional.of(ready),
                Optional.of(new IssueControlState(false, "OPEN", EPOCH, "RECOVERY")));

        var summary = useCase.republishDue();

        // 훑기 전 확인 1번 + 전송 직전 확인 2번(두 번째에서 멈춤). 다음 페이지는 보지 않는다
        verify(issueStore, times(3)).findIssueControl(COUPON_ID);
        verify(publisher, times(1)).publish(any(), any());
        assertCounts(summary, 1, 1, 0);
    }

    @Test
    void 전송_직전에_epoch가_바뀌었으면_보내지_않는다() {
        target(CouponStatus.OPEN);
        requests(Map.of("req-1", accepted(7, OLD)));
        when(issueStore.findIssueControl(COUPON_ID)).thenReturn(
                Optional.of(new IssueControlState(true, "OPEN", EPOCH, null)),
                Optional.of(new IssueControlState(true, "OPEN", EPOCH + 1, null)));

        var summary = useCase.republishDue();

        verifyNoInteractions(publisher);
        assertCounts(summary, 0, 0, 0);
    }

    @Test
    void 정리된_CLOSED_쿠폰은_훑지_않고_정리_전이면_훑는다() {
        when(queryRepository.findTargets(any())).thenReturn(List.of(
                new RepublishTargetCoupon(1L, CouponStatus.CLOSED, EPOCH),
                new RepublishTargetCoupon(2L, CouponStatus.CLOSED, EPOCH)));
        when(issueStore.hasRequests(1L)).thenReturn(false);
        when(issueStore.hasRequests(2L)).thenReturn(true);

        useCase.republishDue();

        verify(issueStore, never()).scanRequests(eq(1L), anyInt(), any());
        verify(issueStore).scanRequests(eq(2L), eq(200), any());
    }

    @Test
    void 복구_중이거나_epoch가_DB와_다르거나_issue_control이_없으면_그_쿠폰을_훑지_않는다() {
        when(queryRepository.findTargets(any())).thenReturn(List.of(
                new RepublishTargetCoupon(1L, CouponStatus.OPEN, EPOCH),
                new RepublishTargetCoupon(2L, CouponStatus.OPEN, EPOCH),
                new RepublishTargetCoupon(3L, CouponStatus.OPEN, EPOCH)));
        when(issueStore.findIssueControl(1L))
                .thenReturn(Optional.of(new IssueControlState(false, "OPEN", EPOCH, "RECOVERY")));
        when(issueStore.findIssueControl(2L))
                .thenReturn(Optional.of(new IssueControlState(true, "OPEN", EPOCH + 1, null)));
        when(issueStore.findIssueControl(3L)).thenReturn(Optional.empty());

        useCase.republishDue();

        verify(issueStore, never()).scanRequests(anyLong(), anyInt(), any());
        verifyNoInteractions(publisher);
    }

    @Test
    void 적체_보류_중이어도_준비된_쿠폰은_재발행한다() {
        target(CouponStatus.OPEN);
        when(issueStore.findIssueControl(COUPON_ID))
                .thenReturn(Optional.of(new IssueControlState(true, "OPEN", EPOCH, "BACKLOG")));
        requests(Map.of("req-1", accepted(7, OLD)));

        assertThat(useCase.republishDue().republished()).isEqualTo(1);
    }

    @Test
    void 전송이_실패해도_기록을_지우지_않고_실패로_센다() {
        target(CouponStatus.OPEN);
        requests(Map.of("req-1", accepted(7, OLD)));
        when(publisher.publish(any(), any())).thenReturn(PublishResult.TIMED_OUT);

        var summary = useCase.republishDue();

        assertCounts(summary, 1, 0, 0);
        assertThat(meterRegistry.get(CouponRepublishUseCase.REPUBLISHED_METRIC).tag("result", "timed_out").counter()
                .count()).isEqualTo(1);
        assertThat(meterRegistry.get(CouponRepublishUseCase.LAST_REPUBLISHED_METRIC).gauge().value()).isZero();
        verify(issueStore, never()).markPublished(anyLong(), anyString());
    }

    @Test
    void 한_쿠폰의_오류는_그_쿠폰만_건너뛰고_다음_쿠폰을_계속한다() {
        when(queryRepository.findTargets(any())).thenReturn(List.of(
                new RepublishTargetCoupon(1L, CouponStatus.OPEN, EPOCH),
                new RepublishTargetCoupon(COUPON_ID, CouponStatus.OPEN, EPOCH)));
        when(issueStore.findIssueControl(1L)).thenThrow(new CouponIssueStoreUnavailableException("redis", null));
        requests(Map.of("req-1", accepted(7, OLD)));

        var summary = useCase.republishDue();

        assertCounts(summary, 1, 1, 1);
    }

    @Test
    void 한_쿠폰에서_연속_실패가_상한에_닿으면_그_쿠폰의_남은_전송을_멈추고_다음_쿠폰을_계속한다() {
        when(queryRepository.findTargets(any())).thenReturn(List.of(
                new RepublishTargetCoupon(COUPON_ID, CouponStatus.OPEN, EPOCH),
                new RepublishTargetCoupon(2L, CouponStatus.OPEN, EPOCH)));
        requestPages(COUPON_ID, List.of(
                Map.of("req-1", accepted(7, OLD), "req-2", accepted(8, OLD)),
                Map.of("req-3", accepted(9, OLD), "req-4", accepted(10, OLD))));
        requestPages(2L, List.of(Map.of("req-9", accepted(11, OLD))));
        when(publisher.publish(argThat(event -> event != null && event.couponId() == COUPON_ID), any()))
                .thenReturn(PublishResult.TIMED_OUT, PublishResult.FAILED, PublishResult.TIMED_OUT);

        RepublishSummary summary = useCase.republishDue();

        // 페이지를 넘겨도 연속으로 센다: 기본 상한 3건에서 멈춰 넷째 요청은 보내지 않는다
        verify(publisher, times(3)).publish(argThat(event -> event != null && event.couponId() == COUPON_ID), any());
        verify(publisher).publish(argThat(event -> event != null && event.couponId() == 2L), any());
        assertThat(summary.coupons()).containsExactly(
                new CouponResult(COUPON_ID, 3, 0, 3, Ending.CONSECUTIVE_FAILURES),
                new CouponResult(2L, 1, 1, 0, Ending.COMPLETED));
        assertThat(summary.stoppedCoupons()).isEqualTo(1);
        assertThat(summary.failed()).isEqualTo(3);
        // 멈췄어도 미확정 요청을 봤으므로 key 만료는 늦춘다
        verify(issueStore).extendKeysExpiry(COUPON_ID, RETAIN_UNTIL);
    }

    @Test
    void 중간에_ACK가_끼면_연속_실패를_다시_센다() {
        target(CouponStatus.OPEN);
        requestPages(COUPON_ID, List.of(
                Map.of("req-1", accepted(7, OLD)), Map.of("req-2", accepted(8, OLD)),
                Map.of("req-3", accepted(9, OLD)), Map.of("req-4", accepted(10, OLD)),
                Map.of("req-5", accepted(11, OLD))));
        when(publisher.publish(any(), any())).thenReturn(PublishResult.FAILED, PublishResult.FAILED,
                PublishResult.ACKED, PublishResult.FAILED, PublishResult.FAILED);

        RepublishSummary summary = useCase.republishDue();

        verify(publisher, times(5)).publish(any(), any());
        assertThat(summary.coupons()).containsExactly(new CouponResult(COUPON_ID, 5, 1, 4, Ending.COMPLETED));
        assertThat(summary.stoppedCoupons()).isZero();
    }

    @Test
    void DB에_결과가_없는_요청을_본_쿠폰은_key_만료를_지금_더하기_여유까지_늦춘다() {
        target(CouponStatus.CLOSED);
        requests(Map.of("req-dlt", accepted(7, OLD)));
        when(dltLogRepository.findUnresolvedRequestIds(COUPON_ID)).thenReturn(List.of("req-dlt"));

        useCase.republishDue();

        // DLT에서 기다리는 요청도 DB 미확정이다(보내지는 않는다)
        verifyNoInteractions(publisher);
        verify(issueStore).extendKeysExpiry(COUPON_ID, RETAIN_UNTIL);
    }

    @Test
    void DB_결과가_모두_있거나_대기_시간_전_요청만_있으면_key_만료를_건드리지_않는다() {
        target(CouponStatus.CLOSED);
        requests(Map.of(
                "req-recorded", accepted(7, OLD),
                "req-fresh", accepted(8, NOW.minusSeconds(10))));
        when(queryRepository.findRecordedIds(eq(COUPON_ID), any())).thenReturn(List.of("req-recorded"));

        useCase.republishDue();

        verify(issueStore, never()).extendKeysExpiry(anyLong(), any());
    }

    @Test
    void key_만료_연장이_실패해도_재발행_결과는_그대로다() {
        target(CouponStatus.OPEN);
        requests(Map.of("req-1", accepted(7, OLD)));
        when(issueStore.extendKeysExpiry(anyLong(), any())).thenThrow(new CouponIssueStoreUnavailableException("redis", null));

        assertCounts(useCase.republishDue(), 1, 1, 0);
    }

    private static void assertCounts(RepublishSummary summary, int republished, int acked, int failedCoupons) {
        assertThat(summary.republished()).isEqualTo(republished);
        assertThat(summary.acked()).isEqualTo(acked);
        assertThat(summary.failedCoupons()).isEqualTo(failedCoupons);
    }

    private void target(CouponStatus status) {
        when(queryRepository.findTargets(any())).thenReturn(List.of(new RepublishTargetCoupon(COUPON_ID, status, EPOCH)));
    }

    @SuppressWarnings("unchecked")
    private void requests(Map<String, String> values) {
        doAnswer(invocation -> {
            List<RequestEntry> page = new ArrayList<>();
            values.forEach((id, value) -> page.add(new RequestEntry(id, value)));
            ((Consumer<List<RequestEntry>>) invocation.getArgument(2)).accept(page);
            return null;
        }).when(issueStore).scanRequests(eq(COUPON_ID), anyInt(), any());
    }

    /** 페이지를 차례로 넘긴다. 콜백이 던지면(전송 직전 차단·연속 실패) 남은 페이지를 넘기지 않는다 */
    private void requestPages(List<Map<String, String>> pages) {
        requestPages(COUPON_ID, pages);
    }

    @SuppressWarnings("unchecked")
    private void requestPages(long couponId, List<Map<String, String>> pages) {
        doAnswer(invocation -> {
            for (Map<String, String> values : pages) {
                List<RequestEntry> page = new ArrayList<>();
                values.forEach((id, value) -> page.add(new RequestEntry(id, value)));
                ((Consumer<List<RequestEntry>>) invocation.getArgument(2)).accept(page);
            }
            return null;
        }).when(issueStore).scanRequests(eq(couponId), anyInt(), any());
    }

    private static String accepted(long memberId, Instant acceptedAt) {
        return IssueRequestRecord.accepted(memberId, acceptedAt, acceptedAt.plus(Duration.ofDays(30))).format();
    }
}
