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
import com.hapbang.coupon.config.CouponIssueDeliveryProperties;
import com.hapbang.coupon.domain.CouponStatus;
import com.hapbang.coupon.domain.IssueRequestRecord;
import com.hapbang.coupon.out.CouponDltLogRepository;
import com.hapbang.coupon.out.CouponRepublishQueryRepository;
import com.hapbang.coupon.out.RepublishTargetCoupon;
import com.hapbang.shared.coupon.event.CouponIssueRequested;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

// 재발행 흐름: 어떤 쿠폰·요청을 다시 보내는지(DB 부재만, DB 존재·DLT 미처리·보상 완료 제외, 복구 중 쿠폰 제외, 전송 직전 차단 확인)와
// 실린 값을 본다.
// 요청 하나의 판정 규칙은 RepublishRuleTest, HSCAN·기록 형식은 Redis 테스트가 본다
class CouponRepublishUseCaseTest {

    private static final long COUPON_ID = 42;
    private static final long EPOCH = 2;
    private static final Instant NOW = Instant.parse("2026-11-08T03:00:00Z");
    private static final Instant OLD = NOW.minus(Duration.ofMinutes(5));
    private static final Instant EXPIRES_AT = OLD.plus(Duration.ofDays(30));

    private final CouponRepublishQueryRepository queryRepository = mock();
    private final CouponDltLogRepository dltLogRepository = mock();
    private final CouponIssueStore issueStore = mock();
    private final CouponIssueEventPublisher publisher = mock();
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private CouponRepublishUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new CouponRepublishUseCase(queryRepository, dltLogRepository, issueStore, publisher,
                CouponIssueDeliveryProperties.defaults(), registry(meterRegistry), Clock.fixed(NOW, ZoneOffset.UTC));
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

        assertThat(summary).isEqualTo(new CouponRepublishUseCase.RepublishSummary(1, 1, 0));
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
        assertThat(summary).isEqualTo(new CouponRepublishUseCase.RepublishSummary(1, 1, 0));
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
        assertThat(summary).isEqualTo(new CouponRepublishUseCase.RepublishSummary(0, 0, 0));
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

        assertThat(summary).isEqualTo(new CouponRepublishUseCase.RepublishSummary(1, 0, 0));
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

        assertThat(summary).isEqualTo(new CouponRepublishUseCase.RepublishSummary(1, 1, 1));
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

    /** 페이지를 차례로 넘긴다. 콜백이 던지면(전송 직전 차단) 남은 페이지를 넘기지 않는다 */
    @SuppressWarnings("unchecked")
    private void requestPages(List<Map<String, String>> pages) {
        doAnswer(invocation -> {
            for (Map<String, String> values : pages) {
                List<RequestEntry> page = new ArrayList<>();
                values.forEach((id, value) -> page.add(new RequestEntry(id, value)));
                ((Consumer<List<RequestEntry>>) invocation.getArgument(2)).accept(page);
            }
            return null;
        }).when(issueStore).scanRequests(eq(COUPON_ID), anyInt(), any());
    }

    private static String accepted(long memberId, Instant acceptedAt) {
        return IssueRequestRecord.accepted(memberId, acceptedAt, acceptedAt.plus(Duration.ofDays(30))).format();
    }
}
