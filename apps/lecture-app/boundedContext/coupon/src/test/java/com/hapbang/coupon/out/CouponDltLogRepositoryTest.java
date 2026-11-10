package com.hapbang.coupon.out;

import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import com.hapbang.coupon.domain.CouponDltKind;
import com.hapbang.coupon.domain.CouponDltLog;
import com.hapbang.coupon.domain.CouponDltStatus;
import com.hapbang.testsupport.RepositoryTest;

import jakarta.persistence.EntityManager;

@RepositoryTest
class CouponDltLogRepositoryTest {

    private static final String TOPIC = "coupon.events.v1";
    private static final Duration LEASE = Duration.ofMinutes(1);

    @Autowired
    private CouponDltLogRepository couponDltLogRepository;

    @Autowired
    private EntityManager entityManager;

    private static CouponDltLog isolate(long offset, Long couponId, String requestId) {
        return CouponDltLog.isolate(new CouponDltLog.Source(TOPIC, 0, offset, String.valueOf(couponId)), couponId,
                requestId, "{}", "원인", NOW);
    }

    @Test
    void 주문_이벤트_격리_기록은_종류_ORDER로_저장되고_메시지_위치로만_가린다() {
        CouponDltLog order = couponDltLogRepository.saveAndFlush(CouponDltLog.isolateOrderEvent(
                new CouponDltLog.Source("lecture.events.v1", 0, 1, "order-item:901"), "{}", "원인", NOW));
        couponDltLogRepository.saveAndFlush(CouponDltLog.isolateOrderEvent(
                new CouponDltLog.Source("lecture.events.v1", 0, 2, "order-item:901"), "{}", "원인", NOW));
        entityManager.clear();

        assertThat(couponDltLogRepository.findById(order.getId())).get()
                .extracting(CouponDltLog::getKind).isEqualTo(CouponDltKind.ORDER);
        assertThat(couponDltLogRepository.findById(couponDltLogRepository.saveAndFlush(isolate(5, 42L, "req-1"))
                .getId())).get().extracting(CouponDltLog::getKind).isEqualTo(CouponDltKind.ISSUE);
        assertThat(couponDltLogRepository.countUnresolved()).isEqualTo(3);
    }

    @Test
    void 같은_요청의_PENDING은_하나이고_처리한_뒤에는_다시_격리할_수_있다() {
        CouponDltLog first = couponDltLogRepository.saveAndFlush(isolate(1, 42L, "req-1"));

        assertThat(couponDltLogRepository.existsPendingFor(42L, "req-1")).isTrue();
        assertThat(couponDltLogRepository.existsPendingFor(42L, "req-2")).isFalse();
        entityManager.clear();

        CouponDltLog replayed = couponDltLogRepository.findById(first.getId()).orElseThrow();
        replayed.claimReplay(ADMIN_ID, NOW, LEASE);
        replayed.completeReplay(NOW);
        couponDltLogRepository.saveAndFlush(replayed);
        couponDltLogRepository.saveAndFlush(isolate(3, 42L, "req-1"));

        assertThat(couponDltLogRepository.countByStatus(CouponDltStatus.PENDING)).isEqualTo(1);
        assertThat(couponDltLogRepository.findByStatus(CouponDltStatus.REPLAYED, PageRequest.of(0, 10)))
                .extracting(CouponDltLog::getSourceOffset).containsExactly(1L);
        // 위반 뒤에는 트랜잭션이 중단되므로 마지막에 본다
        assertThatThrownBy(() -> couponDltLogRepository.saveAndFlush(isolate(4, 42L, "req-1")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(CouponDltLogRepository.PENDING_REQUEST_UNIQUE);
    }

    @Test
    void 재발행_선점_뒤_다시_격리된_같은_요청은_새_PENDING이_되고_그때는_선점을_PENDING으로_되돌릴_수_없다() {
        CouponDltLog first = couponDltLogRepository.saveAndFlush(isolate(1, 42L, "req-1"));
        first.claimReplay(ADMIN_ID, NOW, LEASE);
        couponDltLogRepository.saveAndFlush(first);

        // 선점(REPLAYING)이 커밋된 뒤 재발행 메시지가 다시 격리된다
        couponDltLogRepository.saveAndFlush(isolate(2, 42L, "req-1"));
        assertThat(couponDltLogRepository.existsPendingFor(42L, "req-1")).isTrue();

        first.revertReplay(NOW);
        assertThatThrownBy(() -> couponDltLogRepository.saveAndFlush(first))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(CouponDltLogRepository.PENDING_REQUEST_UNIQUE);
    }

    @Test
    void PENDING과_재발행_선점_중인_REPLAYING을_미처리로_세고_선점_시각을_저장한다() {
        couponDltLogRepository.saveAndFlush(isolate(1, 42L, "req-pending"));
        CouponDltLog replaying = isolate(2, 42L, "req-replaying");
        replaying.claimReplay(ADMIN_ID, NOW, LEASE);
        couponDltLogRepository.saveAndFlush(replaying);
        CouponDltLog replayed = isolate(3, 42L, "req-replayed");
        replayed.claimReplay(ADMIN_ID, NOW, LEASE);
        replayed.completeReplay(NOW);
        couponDltLogRepository.saveAndFlush(replayed);
        CouponDltLog rejected = isolate(4, 42L, "req-rejected");
        rejected.reject(ADMIN_ID, "사유", NOW, LEASE);
        couponDltLogRepository.saveAndFlush(rejected);
        couponDltLogRepository.saveAndFlush(isolate(5, 43L, "req-other"));
        couponDltLogRepository.saveAndFlush(isolate(6, null, null));
        entityManager.clear();

        assertThat(couponDltLogRepository.findUnresolvedRequestIds(42L))
                .containsExactlyInAnyOrder("req-pending", "req-replaying");
        assertThat(couponDltLogRepository.countUnresolved()).isEqualTo(4);
        assertThat(couponDltLogRepository.findById(replaying.getId()).orElseThrow().getReplayClaimedAt())
                .isEqualTo(NOW);
        assertThat(couponDltLogRepository.findByStatus(CouponDltStatus.REPLAYING, PageRequest.of(0, 10)))
                .extracting(CouponDltLog::getIssueRequestId).containsExactly("req-replaying");
    }

    @Test
    void 같은_메시지_위치는_두_번_기록하지_않고_요청_ID가_없는_역직렬화_실패_기록은_여럿_둘_수_있다() {
        couponDltLogRepository.saveAndFlush(isolate(1, 42L, null));
        couponDltLogRepository.saveAndFlush(isolate(2, 42L, null));
        entityManager.clear();

        assertThat(couponDltLogRepository.existsBySourceTopicAndSourcePartitionAndSourceOffset(TOPIC, 0, 1L)).isTrue();
        assertThat(couponDltLogRepository.findUnresolvedRequestIds(42L)).isEmpty();
        assertThatThrownBy(() -> couponDltLogRepository.saveAndFlush(isolate(1, 43L, "req-9")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(CouponDltLogRepository.SOURCE_UNIQUE);
    }

    @Test
    void 영구_거절과_보상_결과를_저장하고_최신순으로_읽는다() {
        couponDltLogRepository.saveAndFlush(isolate(1, 42L, "req-1"));
        CouponDltLog rejected = couponDltLogRepository.saveAndFlush(isolate(2, 42L, "req-2"));
        rejected.reject(ADMIN_ID, "사유", NOW, LEASE);
        rejected.recordCompensation(com.hapbang.coupon.domain.IssueCompensation.RESTORED, NOW);
        couponDltLogRepository.saveAndFlush(rejected);
        entityManager.clear();

        assertThat(couponDltLogRepository.findAll(PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "id"))))
                .extracting(CouponDltLog::getIssueRequestId).containsExactly("req-2", "req-1");
        assertThat(couponDltLogRepository.findById(rejected.getId()).orElseThrow().getCompensationResult())
                .isEqualTo(com.hapbang.coupon.domain.IssueCompensation.RESTORED);
    }
}
